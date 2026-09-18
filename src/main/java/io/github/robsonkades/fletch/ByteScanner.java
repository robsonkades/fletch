/*
 * Copyright 2026 Robson Kades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Shared byte-level scanning core: buffering/windowing, encoding detection,
 * structural scanning (tags, comments, CDATA, PIs, subtree skipping) and text
 * decoding (entities, CR normalization, attribute-value normalization).
 *
 * <p>Two engines run on top of it: {@link XmlMappingEngine} drives a compiled
 * {@link XmlMapping} in one fused push loop, and {@link XmlCursorEngine} serves
 * the pull-based {@link XmlCursor} API. All state here is scratch owned by
 * the concrete engine instance and reused across documents; none of it is
 * thread-safe.
 *
 * <p>The document view {@code b[0, n)} is either the caller's own bytes
 * (scanned in place), a transcode buffer, or the sliding stream window.
 * {@link #more} is the single refill point: engines that buffered the whole
 * document never trigger it ({@code src} stays {@code null}), so every scan
 * primitive works identically in windowed and fully-buffered modes.
 */
abstract class ByteScanner {

    static final int MAX_TEXT = 16 * 1024 * 1024;
    static final int DEFAULT_WINDOW = 64 * 1024;
    static final int MAX_ATTRIBUTES = 1024;
    private static final int RETAIN = 1 << 20;

    final int window;

    XmlLimits limits = XmlLimits.defaults();
    private long inputBytes;
    private long elements;
    private long lastElementOffset = -1;

    // Verify end tags inside skipped subtrees instead of only counting them.
    boolean strictSkip;
    private int[] nameSpans = new int[32];
    private byte[] nameBytes;
    private int[] attributeSpans;
    // Wide tags use an index into attributeSpans; zero denotes an empty slot.
    // Both arrays are bounded by MAX_ATTRIBUTES and contain no document references.
    private int[] attributeTable;
    private int attributeMask;

    // Document view being scanned (caller bytes, the window, or a transcode buffer).
    byte[] b;
    int n;
    int scanFrom;

    // Streaming source; null while scanning fully-buffered input.
    InputStream src;
    boolean eof;
    long base;      // absolute source offset of b[0]

    // Scratch for cooked values (entity decode, CR / attribute normalization).
    byte[] cook = new byte[256];
    int cookLen;

    byte[] io;      // sliding window / stream buffer
    byte[] trans;   // transcode output buffer

    // Result span of the last readLeafText call: valA[valS, valE), empty when
    // the element was blank. Consumed immediately by the engine.
    byte[] valA;
    int valS;
    int valE;
    private int textBytes;

    ByteScanner(final int window) {
        this.window = Math.max(window, 16);
    }

    // ------------------------------------------------------------------ window refill

    /**
     * Makes progress on the streaming source while preserving
     * {@code b[keep, n)}. Compacts the window when it is full (or when the
     * caller discards everything), growing it for tokens larger than the
     * window. Returns the distance everything shifted left — callers
     * subtract it from their live positions — or {@code -1} when no further
     * data can be produced; {@code -1} guarantees positions are unchanged.
     */
    final int more(final int keep) {
        if (src == null || eof) {
            return -1;
        }
        if (inputBytes == limits.maxInputBytes) {
            probeInputEnd();
            return -1;
        }
        int shift = 0;
        if (keep > 0 && (keep == n || n == b.length)) {
            System.arraycopy(b, keep, b, 0, n - keep);
            n -= keep;
            base += keep;
            shift = keep;
        }
        if (n == b.length) {
            if (b.length >= MAX_TEXT) {
                throw fail("Token exceeds " + MAX_TEXT + " bytes", 0);
            }
            b = Arrays.copyOf(b, (int) Math.min(MAX_TEXT,
                    Math.min((long) b.length << 1, n + limits.maxInputBytes - inputBytes)));
            io = b;
        }
        try {
            final int r = readInput();
            if (r < 0) {
                eof = true;
                return shift == 0 ? -1 : shift;
            }
            n += r;
            return shift;
        } catch (IOException e) {
            throw new XmlException("Error reading XML stream", e);
        }
    }

    /** Largest array the JVM can reliably allocate; also the document-size ceiling for buffered streams. */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    /** Reads the remaining stream fully — for engines that buffer whole documents. */
    final void drainFully() {
        try {
            while (!eof) {
                if (inputBytes == limits.maxInputBytes) {
                    probeInputEnd();
                    break;
                }
                if (n == b.length) {
                    if (b.length >= MAX_ARRAY) {
                        throw fail("Document exceeds the maximum supported size of 2 GiB", 0);
                    }
                    b = Arrays.copyOf(b, (int) Math.min(MAX_ARRAY,
                            Math.min(b.length * 2L, n + limits.maxInputBytes - inputBytes)));
                    io = b;
                }
                final int r = readInput();
                if (r < 0) {
                    eof = true;
                } else {
                    n += r;
                }
            }
        } catch (IOException e) {
            throw new XmlException("Error reading XML stream", e);
        }
        src = null;
    }

    /** Restrict read-ahead to the remaining original-byte budget. */
    private int readInput() throws IOException {
        final int length = (int) Math.min(b.length - n, limits.maxInputBytes - inputBytes);
        int read = src.read(b, n, length);
        if (read == 0) {
            // Some InputStreams make no progress on bulk reads; do not spin forever.
            final int one = src.read();
            if (one < 0) return -1;
            b[n] = (byte) one;
            read = 1;
        }
        if (read > 0) inputBytes += read;
        return read;
    }

    /** One-byte lookahead distinguishes an exact-size stream from an oversized one. */
    private void probeInputEnd() {
        try {
            if (src.read() >= 0) {
                throw new XmlException("Input exceeds " + limits.maxInputBytes
                        + " bytes (byte offset " + inputBytes + ")");
            }
            eof = true;
        } catch (IOException e) {
            throw new XmlException("Error reading XML stream", e);
        }
    }

    /**
     * Points the scanner at a streaming source, resetting window state. The
     * caller decides how to consume it ({@link #drainFully} or incremental
     * {@link #more} refills).
     */
    final void beginStream(final InputStream stream) {
        if (io == null) {
            io = new byte[(int) Math.min(window, limits.maxInputBytes)];
        }
        b = io;
        n = 0;
        base = 0;
        eof = false;
        src = stream;
        inputBytes = 0;
        elements = 0;
        lastElementOffset = -1;
    }

    /**
     * Drops every reference to caller-supplied data — the document array (or
     * view) and the stream. Engines call it from a finally block covering
     * source preparation and parsing, before any return to the pool.
     */
    final void releaseSource() {
        src = null;
        b = null;
        valA = null;
        limits = XmlLimits.defaults();
    }

    /** Drops outlier scratch buffers before either engine is returned to a pool. */
    final void trimForReuse() {
        limits = XmlLimits.defaults();
        if (io != null && io.length > RETAIN) io = null;
        if (trans != null && trans.length > RETAIN) trans = null;
        if (cook.length > RETAIN) cook = new byte[256];
        if (nameBytes != null && nameBytes.length > RETAIN) nameBytes = null;
        if (nameSpans.length > RETAIN / Integer.BYTES) nameSpans = new int[32];
        if (attributeSpans != null && attributeSpans.length > RETAIN / Integer.BYTES) attributeSpans = null;
    }

    // ------------------------------------------------------------------ encoding detection

    final void prepare(final byte[] doc, final int len, final boolean sniff) {
        src = null;
        eof = false;
        base = 0;
        elements = 0;
        lastElementOffset = -1;
        checkInputSize(len);
        int from = 0;
        if (sniff && len >= 2) {
            final int b0 = doc[0] & 0xFF;
            final int b1 = doc[1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) {
                utf16(doc, 2, len, true);
                return;
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                utf16(doc, 2, len, false);
                return;
            }
            if (b0 == 0x00 && b1 == '<') {
                utf16(doc, 0, len, true);
                return;
            }
            if (b0 == '<' && b1 == 0x00) {
                utf16(doc, 0, len, false);
                return;
            }
        }
        if (len >= 3 && (doc[0] & 0xFF) == 0xEF && (doc[1] & 0xFF) == 0xBB && (doc[2] & 0xFF) == 0xBF) {
            from = 3;
        }
        if (sniff && declaresLatin1(doc, from, len)) {
            latin1(doc, from, len);
            return;
        }
        this.b = doc;
        this.n = len;
        this.scanFrom = from;
    }

    final void checkInputSize(final long length) {
        if (length > limits.maxInputBytes) {
            throw new XmlException("Input exceeds " + limits.maxInputBytes + " bytes (byte offset 0)");
        }
    }

    /** Count String.getBytes(UTF_8)'s output before allocating it when bounded. */
    final void checkStringSize(final String xml) {
        if (limits.maxInputBytes == Long.MAX_VALUE) return;
        checkInputSize(xml.length()); // Each UTF-16 code unit contributes at least one byte.
        long length = 0;
        for (int i = 0; i < xml.length(); i++) {
            final char c = xml.charAt(i);
            if (c < 0x80) length++;
            else if (c < 0x800) length += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < xml.length()
                    && Character.isLowSurrogate(xml.charAt(i + 1))) {
                length += 4;
                i++;
            } else {
                // Match the JDK encoder's one-byte replacement for an unpaired surrogate.
                length += Character.isSurrogate(c) ? 1 : 3;
            }
            checkInputSize(length);
        }
    }

    /** Called once a start tag is identified, before allocating its cursor or draft. */
    final void checkElement(final int depth, final int offset) {
        if (limits == XmlLimits.defaults()) return;
        if (depth > limits.maxDepth) throw fail("Element depth exceeds " + limits.maxDepth, offset);
        if (limits.maxElements == Long.MAX_VALUE) return;
        final long absolute = base + offset;
        if (absolute <= lastElementOffset) return; // Cursor replay over already counted input.
        if (elements == limits.maxElements) throw fail("Element count exceeds " + limits.maxElements, offset);
        elements++;
        lastElementOffset = absolute;
    }

    /** Streaming variant of {@link #prepare}: legacy encodings drain the stream first. */
    final void sniffStream() {
        int from = 0;
        if (n >= 2) {
            final int b0 = b[0] & 0xFF;
            final int b1 = b[1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) {
                drainFully();
                utf16(b, 2, n, true);
                return;
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                drainFully();
                utf16(b, 2, n, false);
                return;
            }
            if (b0 == 0x00 && b1 == '<') {
                drainFully();
                utf16(b, 0, n, true);
                return;
            }
            if (b0 == '<' && b1 == 0x00) {
                drainFully();
                utf16(b, 0, n, false);
                return;
            }
        }
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            from = 3;
        }
        if (declaresLatin1(b, from, n)) {
            drainFully();
            latin1(b, from, n);
            return;
        }
        this.scanFrom = from;
    }

    /**
     * Inspects the XML declaration's encoding pseudo-attribute. UTF-8 (and
     * US-ASCII) return {@code false} — scan in place; Latin-1 returns
     * {@code true}; anything else is rejected.
     */
    private boolean declaresLatin1(final byte[] a, final int from, final int lim) {
        if (lim - from < 6 || a[from] != '<' || a[from + 1] != '?'
                || a[from + 2] != 'x' || a[from + 3] != 'm' || a[from + 4] != 'l'
                || (a[from + 5] & 0xFF) > ' ') {
            return false;
        }
        int end = Swar.memchr(a, from, Math.min(lim, from + 256), '>');
        if (end < 0) {
            end = Math.min(lim, from + 256);
        }
        final int e = indexOf(a, from + 5, end, ENCODING);
        if (e < 0) {
            return false;
        }
        int j = e + ENCODING.length;
        while (j < end && (a[j] & 0xFF) <= ' ') j++;
        if (j >= end || a[j] != '=') {
            return false;
        }
        j++;
        while (j < end && (a[j] & 0xFF) <= ' ') j++;
        if (j >= end) {
            return false;
        }
        final int q = a[j] & 0xFF;
        if (q != '"' && q != '\'') {
            return false;
        }
        final int vs = ++j;
        final int ve = Swar.memchr(a, j, end, q);
        if (ve < 0) {
            return false;
        }
        if (ciEquals(a, vs, ve, "utf-8") || ciEquals(a, vs, ve, "utf8")
                || ciEquals(a, vs, ve, "us-ascii") || ciEquals(a, vs, ve, "ascii")) {
            return false;
        }
        if (ciEquals(a, vs, ve, "iso-8859-1") || ciEquals(a, vs, ve, "iso8859-1")
                || ciEquals(a, vs, ve, "latin-1") || ciEquals(a, vs, ve, "latin1")) {
            return true;
        }
        throw new XmlException("Unsupported encoding: " + new String(a, vs, ve - vs, StandardCharsets.ISO_8859_1));
    }

    private static final byte[] ENCODING = "encoding".getBytes(StandardCharsets.US_ASCII);

    private static int indexOf(final byte[] a, final int from, final int to, final byte[] pat) {
        outer:
        for (int i = from; i + pat.length <= to; i++) {
            for (int k = 0; k < pat.length; k++) {
                if (a[i + k] != pat[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean ciEquals(final byte[] a, final int s, final int e, final String lower) {
        if (e - s != lower.length()) {
            return false;
        }
        for (int i = 0; i < lower.length(); i++) {
            int c = a[s + i] & 0xFF;
            if (c >= 'A' && c <= 'Z') c |= 0x20;
            if (c != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void latin1(final byte[] a, final int from, final int lim) {
        int extra = 0;
        for (int i = from; i < lim; i++) {
            if (a[i] < 0) extra++;
        }
        final int out = (lim - from) + extra;
        if (trans == null || trans.length < out) {
            trans = new byte[Math.max(out, 1024)];
        }
        int w = 0;
        for (int i = from; i < lim; i++) {
            final int c = a[i] & 0xFF;
            if (c < 0x80) {
                trans[w++] = (byte) c;
            } else {
                trans[w++] = (byte) (0xC0 | (c >> 6));
                trans[w++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        this.b = trans;
        this.n = w;
        this.base = 0;
        this.scanFrom = 0;
    }

    private void utf16(final byte[] doc, final int off, final int len, final boolean bigEndian) {
        final String s = new String(doc, off, len - off,
                bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
        final byte[] u = s.getBytes(StandardCharsets.UTF_8);
        this.b = u;
        this.n = u.length;
        this.base = 0;
        this.scanFrom = 0;
    }

    // ------------------------------------------------------------------ leaf text

    /**
     * Reads the text content of a leaf element starting just after its start
     * tag, verifying the end tag against the complete expected name bytes.
     * The expected name must remain stable across window refills (mapping
     * names live in its immutable blob; cursors use fully buffered input).
     * The value lands in {@link #valA}{@code [}{@link
     * #valS}{@code , }{@link #valE}{@code )}, already trimmed; an empty span
     * means the element was blank. Returns the index just past the element's
     * end tag. The dominant single-run case scans and validates the bytes,
     * producing a zero-copy span (anchored in the window across refills);
     * entities, CR normalization, CDATA and nested markup take the cooked
     * path.
     */
    final int readLeafText(final byte[] name, final int off, final int len, int start, final int depth) {
        textBytes = 0;
        // One fused scan finds the '<' and detects dirtiness ('&' entity or
        // '\r' normalization) on the way, so clean values — the dominant
        // case — need no decoding buffer. The scan resumes where it left off after
        // a refill instead of restarting from the value's first byte.
        boolean dirty = false;
        int scan = start;
        int lt;
        while (true) {
            lt = dirty ? Swar.memchr(b, scan, n, '<')
                       : Swar.memchr3(b, scan, n, '<', '&', '\r');
            if (lt >= 0 && b[lt] != '<') {
                dirty = true;
                scan = lt + 1;
                continue;
            }
            if (lt >= 0 && lt + 2 <= n) break;
            checkTextSize((lt >= 0 ? lt : n) - start, start);
            if (src != null && n == b.length && b.length >= MAX_TEXT) {
                return readLongLeaf(name, off, len, start, depth);
            }
            final int resume = lt >= 0 ? lt : n;
            final int sh = more(start);           // keep the value span alive
            if (sh < 0) throw fail("Unexpected end of document", n);
            start -= sh;
            scan = resume - sh;
        }
        checkTextSize(lt - start, start);
        if (b[lt + 1] == '/') {
            int s, e, ret;
            while (true) {
                s = lt + 2;
                e = nameEnd(s);
                if (e < n) {
                    ret = closeAngleOr(e);
                    if (ret >= 0) break;
                }
                if (src != null && n == b.length && b.length >= MAX_TEXT) {
                    return readLongLeaf(name, off, len, start, depth);
                }
                final int sh = more(start);       // keep the value span alive too
                if (sh < 0) throw fail("Malformed end tag", lt);
                start -= sh;
                lt -= sh;
            }
            if (!nameMatches(name, off, len, s, e)) throw fail("Mismatched end tag", lt);
            validateXmlBytes(start, lt, true);
            final int vs = lstrip(b, start, lt);
            final int ve = rstrip(b, vs, lt);
            if (vs >= ve) {
                valA = b;
                valS = 0;
                valE = 0;
            } else if (!dirty) {
                valA = b;
                valS = vs;
                valE = ve;
            } else {
                cookLen = 0;
                decodeText(vs, ve);
                setCookedTrimmed();
            }
            return ret;
        }
        cookLen = 0;
        final boolean nonWs = appendPiece(start, lt);
        endRun(0, nonWs);
        return readLeafMixed(name, off, len, lt, depth);
    }

    /** Spill a near-limit value so its closing markup need not fit beside it in the window. */
    private int readLongLeaf(final byte[] name, final int off, final int len, int start, final int depth) {
        cookLen = 0;
        boolean nonWs = false;
        int lt;
        while ((lt = Swar.memchr(b, start, n, '<')) < 0) {
            final int cut = safeCut(start, n);
            nonWs |= appendPiece(start, cut);
            final int sh = more(cut);
            if (sh < 0) throw fail("Unexpected end of document", cut);
            start = cut - sh;
        }
        nonWs |= appendPiece(start, lt);
        endRun(0, nonWs);
        return readLeafMixed(name, off, len, lt, depth);
    }

    /** Rare leaf shapes: CDATA sections, comments, PIs or child elements. */
    private int readLeafMixed(final byte[] name, final int off, final int len, final int firstLt, final int depth) {
        int i = firstLt;
        int d = 1;
        while (true) {
            while (i + 2 > n) {
                final int sh = more(i);
                if (sh < 0) throw fail("Unexpected end of document", i);
                i -= sh;
            }
            final int c = b[i + 1] & 0xFF;
            if (c == '/') {
                int s, e, nx;
                while (true) {
                    s = i + 2;
                    e = nameEnd(s);
                    if (e < n) {
                        nx = closeAngleOr(e);
                        if (nx >= 0) break;
                    }
                    final int sh = more(i);
                    if (sh < 0) throw fail("Malformed end tag", i);
                    i -= sh;
                }
                d--;
                if (d == 0) {
                    if (!nameMatches(name, off, len, s, e)) throw fail("Mismatched end tag", i);
                    setCookedTrimmed();
                    return nx;
                }
                if (!stackNameMatches(d - 1, s, e)) throw fail("Mismatched end tag", i);
                i = nx;
            } else if (c == '!') {
                while (n - i < 4) {
                    final int sh = more(i);
                    if (sh < 0) throw fail("Invalid markup", i);
                    i -= sh;
                }
                if (b[i + 2] == '-' && b[i + 3] == '-') {
                    i = skipComment(i);
                } else if (b[i + 2] == '[') {
                    while (n - i < 9) {
                        final int sh = more(i);
                        if (sh < 0) throw fail("Invalid markup", i);
                        i -= sh;
                    }
                    checkCdataStart(i);
                    int end;
                    while ((end = cdataEndOr(i + 9)) < 0) {
                        int contentEnd = n;
                        if (contentEnd > i + 9 && b[contentEnd - 1] == ']') contentEnd--;
                        if (contentEnd > i + 9 && b[contentEnd - 1] == ']') contentEnd--;
                        checkRemainingText(contentEnd - i - 9, i + 9);
                        final int sh = more(i);   // wanted content: anchor the whole section
                        if (sh < 0) throw fail("Unterminated CDATA section", i);
                        i -= sh;
                    }
                    appendCdata(i + 9, end);
                    i = end + 3;
                } else {
                    throw fail("DTD is not supported", i);
                }
            } else if (c == '?') {
                i = skipPi(i);
            } else {
                int s, e, gt;
                while (true) {
                    s = i + 1;
                    e = nameEnd(s);
                    if (e < n) {
                        if (e == s) throw fail("Invalid markup", i);
                        gt = tagEndOr(e);
                        if (gt >= 0) break;
                    }
                    final int sh = more(i);
                    if (sh < 0) throw fail("Unterminated start tag", i);
                    i -= sh;
                }
                checkElement(depth + d, i);
                if (b[gt - 1] != '/') {
                    pushName(d - 1, s, e);
                    d++;
                }
                i = gt + 1;
            }
            // text run up to the next markup, streamed in safe pieces
            final int mark = cookLen;
            boolean nonWs = false;
            int next;
            while ((next = Swar.memchr(b, i, n, '<')) < 0) {
                final int cut = safeCut(i, n);
                nonWs |= appendPiece(i, cut);
                final int sh = more(cut);
                if (sh < 0) throw fail("Unexpected end of document", cut);
                i = cut - sh;
            }
            nonWs |= appendPiece(i, next);
            endRun(mark, nonWs);
            i = next;
        }
    }

    private void setCookedTrimmed() {
        valA = cook;
        valS = lstrip(cook, 0, cookLen);
        valE = rstrip(cook, valS, cookLen);
    }

    /**
     * Decodes one piece of a text run into the cook buffer and reports
     * whether it contained anything beyond whitespace. Pieces of one run
     * accumulate; {@link #endRun} drops runs that were entirely whitespace —
     * mirroring the cursor API, which skips whitespace-only chunks when
     * assembling mixed content.
     */
    private boolean appendPiece(final int s, final int e) {
        if (s >= e) return false;
        countText(e - s, s);
        validateXmlBytes(s, e, true);
        final int from = cookLen;
        decodeText(s, e);
        return !allWs(cook, from, cookLen);
    }

    private void endRun(final int mark, final boolean nonWs) {
        if (!nonWs) cookLen = mark;
    }

    /**
     * Preserves entity references, CRLF, UTF-8 sequences and possible CDATA
     * terminators across a window refill.
     */
    private int safeCut(final int s, final int e) {
        if (s >= e) return e;
        int cut = e;
        if (b[cut - 1] == '\r') cut--;
        // Keep a possible text delimiter and an incomplete UTF-8 sequence intact.
        if (b[e - 1] == ']') {
            cut = Math.min(cut, e - 1);
            if (e - 2 >= s && b[e - 2] == ']') cut = Math.min(cut, e - 2);
        }
        int lead = e - 1;
        while (lead > s && (b[lead] & 0xC0) == 0x80 && e - lead < 4) lead--;
        final int leadByte = b[lead] & 0xFF;
        final int width = leadByte >= 0xF0 ? 4 : leadByte >= 0xE0 ? 3 : leadByte >= 0xC0 ? 2 : 1;
        if (e - lead < width) cut = Math.min(cut, lead);
        final int floor = Math.max(s, e - 11);
        for (int j = cut - 1; j >= floor; j--) {
            final int c = b[j] & 0xFF;
            if (c == '&') {
                cut = j;
                break;
            }
            if (c == ';') break;
        }
        return cut;
    }

    private void appendCdata(final int s, final int e) {
        if (s >= e) return;
        countText(e - s, s);
        validateXmlBytes(s, e, false);
        final int mark = cookLen;
        ensureCook(cookLen + (e - s));
        int j = s;
        while (j < e) {
            final int c = b[j] & 0xFF;
            if (c == '\r') {
                cook[cookLen++] = '\n';
                j++;
                if (j < e && b[j] == '\n') j++;
            } else {
                cook[cookLen++] = (byte) c;
                j++;
            }
        }
        if (allWs(cook, mark, cookLen)) cookLen = mark;
    }

    /** Entity references and CR normalization for element text. */
    private void decodeText(final int s, final int e) {
        ensureCook(cookLen + (e - s));
        int j = s;
        while (j < e) {
            final int c = b[j] & 0xFF;
            if (c == '&') {
                j = entity(j, e);
            } else if (c == '\r') {
                cook[cookLen++] = '\n';
                j++;
                if (j < e && b[j] == '\n') j++;
            } else {
                cook[cookLen++] = (byte) c;
                j++;
            }
        }
    }

    /**
     * Decodes one predefined or character entity reference starting at the
     * {@code &} and appends its expansion; returns the index past the
     * {@code ;}. Anything else — including DTD-declared entities — fails, as
     * DTDs are unsupported by design.
     */
    private int entity(final int amp, final int limit) {
        final int semi = Swar.memchr(b, amp + 1, Math.min(limit, amp + 12), ';');
        if (semi < 0) throw fail("Malformed entity reference", amp);
        final int len = semi - amp - 1;
        if (len >= 2 && b[amp + 1] == '#') {
            int cp = 0;
            if (b[amp + 2] == 'x' || b[amp + 2] == 'X') {
                if (len < 3) throw fail("Malformed character reference", amp);
                for (int j = amp + 3; j < semi; j++) {
                    final int d = hexDigit(b[j] & 0xFF);
                    if (d < 0) throw fail("Malformed character reference", amp);
                    cp = (cp << 4) | d;
                    if (cp > 0x10FFFF) throw fail("Invalid character reference", amp);
                }
            } else {
                for (int j = amp + 2; j < semi; j++) {
                    final int d = (b[j] & 0xFF) - '0';
                    if (d < 0 || d > 9) throw fail("Malformed character reference", amp);
                    cp = cp * 10 + d;
                    if (cp > 0x10FFFF) throw fail("Invalid character reference", amp);
                }
            }
            if (cp < 0x20 && cp != 0x9 && cp != 0xA && cp != 0xD
                    || (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0xFFFE || cp == 0xFFFF) {
                throw fail("Invalid character reference", amp);
            }
            appendCodePoint(cp);
            return semi + 1;
        }
        final byte c1 = len > 0 ? b[amp + 1] : 0;
        if (len == 2 && c1 == 'l' && b[amp + 2] == 't') {
            cook[cookLen++] = '<';
        } else if (len == 2 && c1 == 'g' && b[amp + 2] == 't') {
            cook[cookLen++] = '>';
        } else if (len == 3 && c1 == 'a' && b[amp + 2] == 'm' && b[amp + 3] == 'p') {
            cook[cookLen++] = '&';
        } else if (len == 4 && c1 == 'q' && b[amp + 2] == 'u' && b[amp + 3] == 'o' && b[amp + 4] == 't') {
            cook[cookLen++] = '"';
        } else if (len == 4 && c1 == 'a' && b[amp + 2] == 'p' && b[amp + 3] == 'o' && b[amp + 4] == 's') {
            cook[cookLen++] = '\'';
        } else {
            throw fail("Undeclared entity: " + new String(b, amp + 1, len, StandardCharsets.UTF_8), amp);
        }
        return semi + 1;
    }

    private static int hexDigit(final int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private void appendCodePoint(final int cp) {
        ensureCook(cookLen + 4);
        if (cp < 0x80) {
            cook[cookLen++] = (byte) cp;
        } else if (cp < 0x800) {
            cook[cookLen++] = (byte) (0xC0 | (cp >> 6));
            cook[cookLen++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            cook[cookLen++] = (byte) (0xE0 | (cp >> 12));
            cook[cookLen++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            cook[cookLen++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            cook[cookLen++] = (byte) (0xF0 | (cp >> 18));
            cook[cookLen++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            cook[cookLen++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            cook[cookLen++] = (byte) (0x80 | (cp & 0x3F));
        }
    }

    final void ensureCook(final int min) {
        if (min > MAX_TEXT) throw new XmlException("Text value exceeds " + MAX_TEXT + " bytes");
        if (cook.length < min) cook = Arrays.copyOf(cook, Math.min(MAX_TEXT, Math.max(min, cook.length << 1)));
    }

    final void checkTextSize(final int length, final int offset) {
        if (length > limits.maxTextBytes) throw fail("Text value exceeds " + limits.maxTextBytes + " bytes", offset);
    }

    final void validateXmlBytes(final int start, final int end, final boolean characterData) {
        final int invalid = Utf8.invalidXml(b, start, end, characterData);
        if (invalid >= 0) throw fail("Invalid UTF-8 or XML character data", invalid);
    }

    private void countText(final int length, final int offset) {
        checkRemainingText(length, offset);
        textBytes += length;
    }

    private void checkRemainingText(final int length, final int offset) {
        if (length > limits.maxTextBytes - textBytes) throw fail("Text value exceeds " + limits.maxTextBytes + " bytes", offset);
    }

    final boolean nameMatches(final byte[] expected, final int off, final int len, final int s, final int e) {
        return e - s == len && Arrays.equals(expected, off, off + len, b, s, e);
    }

    /** Buffered input keeps spans; streaming copies active names into one reusable arena. */
    private void pushName(final int depth, final int s, final int e) {
        final int at = depth << 1;
        if (at == nameSpans.length) nameSpans = Arrays.copyOf(nameSpans, at << 1);
        if (src == null) {
            nameSpans[at] = s;
            nameSpans[at + 1] = e;
            return;
        }
        final int start = depth == 0 ? 0 : nameSpans[at - 1];
        if (e - s > MAX_TEXT - start) throw fail("Open element names exceed " + MAX_TEXT + " bytes", s);
        final int end = start + e - s;
        if (nameBytes == null) nameBytes = new byte[Math.max(256, end)];
        else if (end > nameBytes.length) {
            nameBytes = Arrays.copyOf(nameBytes, Math.min(MAX_TEXT, Math.max(end, nameBytes.length << 1)));
        }
        System.arraycopy(b, s, nameBytes, start, e - s);
        nameSpans[at] = start;
        nameSpans[at + 1] = end;
    }

    private boolean stackNameMatches(final int depth, final int s, final int e) {
        final int at = depth << 1;
        final int start = nameSpans[at];
        return nameMatches(src == null ? b : nameBytes, start, nameSpans[at + 1] - start, s, e);
    }

    // ------------------------------------------------------------------ attribute values

    /** True when a raw attribute value needs entity decoding or whitespace normalization. */
    final boolean attrDirty(final int vs, final int ve) {
        for (int j = vs; j < ve; j++) {
            final int c = b[j] & 0xFF;
            if (c == '&' || c == '\t' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    /** Cooks a dirty attribute value (entities, whitespace normalization) into {@link #cook}. */
    final void cookAttrValue(final int vs, final int ve) {
        cookLen = 0;
        ensureCook(ve - vs);
        int j = vs;
        while (j < ve) {
            final int c = b[j] & 0xFF;
            if (c == '&') {
                j = entity(j, ve);
            } else if (c == '\t' || c == '\n') {
                cook[cookLen++] = ' ';
                j++;
            } else if (c == '\r') {
                cook[cookLen++] = ' ';
                j++;
                if (j < ve && b[j] == '\n') j++;
            } else {
                cook[cookLen++] = (byte) c;
                j++;
            }
        }
    }

    // ------------------------------------------------------------------ structural scanning

    /**
     * Crosses an entire unmatched subtree, starting just after its start
     * tag's {@code >}. Only markup structure is inspected, and everything
     * scanned is discarded from the window as it refills — this is the
     * memchr-speed path that makes selective extraction cheap even on
     * streams far larger than memory.
     *
     * <p>By default tags are only counted, not read: a mismatched end tag
     * inside the skipped region goes unnoticed. Under {@link #strictSkip}
     * every end tag is verified against the complete start-tag name.
     * The enclosing name span is copied before the first window refill.
     */
    final int skipSubtree(int i, final int nameStart, final int nameEnd, final int depth) {
        int d = 1;
        if (strictSkip) {
            pushName(0, nameStart, nameEnd);
        }
        while (d > 0) {
            int lt;
            while ((lt = Swar.memchr(b, i, n, '<')) < 0) {
                final int sh = more(n);
                if (sh < 0) throw fail("Unexpected end of document", n);
                i = 0;
            }
            i = lt;
            while (i + 2 > n) {
                final int sh = more(i);
                if (sh < 0) throw fail("Unexpected end of document", i);
                i -= sh;
            }
            final int c = b[i + 1] & 0xFF;
            if (c == '/') {
                if (strictSkip || limits.maxNameBytes != Integer.MAX_VALUE) {
                    int s, e, nx;
                    while (true) {
                        s = i + 2;
                        e = nameEnd(s);
                        if (e < n) {
                            nx = closeAngleOr(e);
                            if (nx >= 0) break;
                        }
                        final int sh = more(i);
                        if (sh < 0) throw fail("Malformed end tag", i);
                        i -= sh;
                    }
                    d--;
                    if (strictSkip && !stackNameMatches(d, s, e)) throw fail("Mismatched end tag", i);
                    i = nx;
                } else {
                    int j = i + 2;
                    int gt;
                    while ((gt = Swar.memchr(b, j, n, '>')) < 0) {
                        final int sh = more(n);       // balance mode: the name is irrelevant
                        if (sh < 0) throw fail("Unexpected end of document", j);
                        j = 0;
                    }
                    d--;
                    i = gt + 1;
                }
            } else if (c == '!') {
                i = bang(i);
            } else if (c == '?') {
                i = skipPi(i);
            } else {
                int s = i + 1;
                int e = nameEnd(s);
                int gt;
                while ((gt = tagEndOr(e)) < 0) {
                    final int sh = more(i);
                    if (sh < 0) throw fail("Unterminated start tag", i);
                    i -= sh;
                    s = i + 1;
                    e = nameEnd(s);
                }
                checkElement(depth + d, i);
                if (b[gt - 1] != '/') {
                    if (strictSkip) {
                        pushName(d, s, e);
                    }
                    d++;
                }
                i = gt + 1;
            }
        }
        return i;
    }

    /**
     * Classifies {@code <!…} constructs: comments and CDATA sections are
     * crossed (their content discarded), {@code <!DOCTYPE} is rejected.
     */
    final int bang(int i) {
        while (n - i < 4) {
            final int sh = more(i);
            if (sh < 0) throw fail("Invalid markup", i);
            i -= sh;
        }
        if (b[i + 2] == '-' && b[i + 3] == '-') {
            return skipComment(i);
        }
        if (b[i + 2] == '[') {
            while (n - i < 9) {
                final int sh = more(i);
                if (sh < 0) throw fail("Invalid markup", i);
                i -= sh;
            }
            checkCdataStart(i);
            return skipCdata(i + 9);
        }
        throw fail("DTD is not supported", i);
    }

    /**
     * Skips past {@code -->}, retaining only a two-byte tail across refills
     * so a terminator straddling a window boundary is still seen.
     */
    private int skipComment(final int i) {
        int j = i + 4;
        while (true) {
            final int dash = Swar.memchr(b, j, n, '-');
            if (dash >= 0 && dash + 2 < n) {
                if (b[dash + 1] == '-') {
                    if (b[dash + 2] != '>') throw fail("Double hyphen in comment", dash);
                    return dash + 3;
                }
                j = dash + 1;
                continue;
            }
            final int keep = dash >= 0 ? dash : n;
            final int sh = more(keep);
            if (sh < 0) throw fail("Unterminated comment", j);
            j = keep - sh;
        }
    }

    /** Skips past {@code ]]>} with the same two-byte-tail strategy. */
    private int skipCdata(final int start) {
        int j = start;
        while (true) {
            final int gt = Swar.memchr(b, j, n, '>');
            if (gt >= 0) {
                if (gt >= 2 && b[gt - 1] == ']' && b[gt - 2] == ']') return gt + 1;
                j = gt + 1;
                continue;
            }
            final int keep = Math.max(j, n - 2);
            final int sh = more(keep);
            if (sh < 0) throw fail("Unterminated CDATA section", j);
            j = keep - sh;
        }
    }

    private void checkCdataStart(final int i) {
        if (i + 9 > n || b[i + 3] != 'C' || b[i + 4] != 'D' || b[i + 5] != 'A'
                || b[i + 6] != 'T' || b[i + 7] != 'A' || b[i + 8] != '[') {
            throw fail("Invalid markup", i);
        }
    }

    /**
     * Returns the index of the first {@code ]} of a {@code ]]>} terminator
     * fully inside the window, or {@code -1} when the section extends past
     * it (the caller anchors and refills — this variant is for CDATA whose
     * content is wanted).
     */
    private int cdataEndOr(final int start) {
        int j = start;
        while (true) {
            final int gt = Swar.memchr(b, j, n, '>');
            if (gt < 0) return -1;
            if (gt - 2 >= start && b[gt - 1] == ']' && b[gt - 2] == ']') return gt - 2;
            j = gt + 1;
        }
    }

    /** Skips past {@code ?>}, retaining a one-byte tail across refills. */
    final int skipPi(final int i) {
        int j = i + 2;
        while (true) {
            final int gt = Swar.memchr(b, j, n, '>');
            if (gt >= 0) {
                if (gt >= 1 && b[gt - 1] == '?') return gt + 1;
                j = gt + 1;
                continue;
            }
            final int keep = Math.max(j, n - 1);
            final int sh = more(keep);
            if (sh < 0) throw fail("Unterminated processing instruction", j);
            j = keep - sh;
        }
    }

    /** Scans to the end of a name; returning {@code n} means "ran out of window". */
    final int nameEnd(final int s) {
        int j = s;
        while (j < n) {
            final int c = b[j] & 0xff;
            if (c == '>' || c == '/' || c == '=' || c <= ' ') break;
            j++;
        }
        if (j - s > limits.maxNameBytes) throw fail("Name exceeds " + limits.maxNameBytes + " bytes", s);
        return j;
    }

    /**
     * Finds the closing {@code >}, checking attribute syntax and duplicate
     * raw names. The caller supplies the end of the element name and anchors
     * the entire tag across refills; {@code -1} means more bytes are needed.
     * Attribute-less tags return immediately without touching scratch arrays.
     */
    final int tagEndOr(int j) {
        if (j < n && b[j] == '>') return j;
        int count = 0;
        while (true) {
            final int beforeSpace = j;
            while (j < n && xmlSpace(b[j])) j++;
            if (j >= n) return -1;
            final int c = b[j] & 0xFF;
            if (c == '>') return j;
            if (c == '/') {
                if (j + 1 >= n) return -1;
                if (b[j + 1] != '>') throw fail("Malformed empty-element tag", j);
                return j + 1;
            }
            if (j == beforeSpace) throw fail("Missing whitespace before attribute", j);
            if (count == limits.maxAttributesPerElement * 2) {
                throw fail("Element exceeds " + limits.maxAttributesPerElement + " attributes", j);
            }
            final int as = j;
            final int ae = nameEnd(as);
            if (ae >= n) return -1;
            if (ae == as) throw fail("Malformed attribute", as);
            j = ae;
            while (j < n && xmlSpace(b[j])) j++;
            if (j >= n) return -1;
            if (b[j++] != '=') throw fail("Malformed attribute", as);
            while (j < n && xmlSpace(b[j])) j++;
            if (j >= n) return -1;
            final int quote = b[j++] & 0xFF;
            if (quote != '"' && quote != '\'') throw fail("Unquoted attribute value", j - 1);
            final int end = Swar.memchr2(b, j, n, quote, '<');
            if (end < 0) {
                checkTextSize(n - j, j);
                return -1;
            }
            if (b[end] == '<') throw fail("Less-than sign in attribute value", end);
            checkTextSize(end - j, j);
            if (attributeSpans == null) attributeSpans = new int[16];
            if (count < 16) {
                for (int k = 0; k < count; k += 2) {
                    if (nameMatches(b, attributeSpans[k], attributeSpans[k + 1] - attributeSpans[k], as, ae)) {
                        throw fail("Duplicate attribute", as);
                    }
                }
            } else {
                checkAttributeDuplicate(as, ae, count);
            }
            if (count == attributeSpans.length) attributeSpans = Arrays.copyOf(attributeSpans, count << 1);
            attributeSpans[count++] = as;
            attributeSpans[count++] = ae;
            j = end + 1;
        }
    }

    /** Keep the small-tag path allocation-free after its first use. */
    private void checkAttributeDuplicate(final int start, final int end, final int count) {
        // Restart at the ninth attribute, including after a refill or a failed parse.
        // Grow at half occupancy; count is the number of span ints, not attributes.
        if (count == 16 || count == attributeMask + 1) {
            attributeMask = (count << 1) - 1;
            if (attributeTable == null || attributeTable.length <= attributeMask) {
                attributeTable = new int[attributeMask + 1];
            } else {
                Arrays.fill(attributeTable, 0, attributeMask + 1, 0);
            }
            for (int k = 0; k < count; k += 2) {
                int slot = attributeSlot(attributeSpans[k], attributeSpans[k + 1]);
                while (attributeTable[slot] != 0) slot = (slot + 1) & attributeMask;
                attributeTable[slot] = k + 1;
            }
        }
        int slot = attributeSlot(start, end);
        while (attributeTable[slot] != 0) {
            final int k = attributeTable[slot] - 1;
            if (nameMatches(b, attributeSpans[k], attributeSpans[k + 1] - attributeSpans[k], start, end)) {
                throw fail("Duplicate attribute", start);
            }
            slot = (slot + 1) & attributeMask;
        }
        attributeTable[slot] = count + 1;
    }

    private int attributeSlot(final int start, final int end) {
        long hash = Swar.hash(b, start, end - start);
        // Unlike mapping selection, this table also mixes the suffix of long names.
        // Full byte comparison above remains the authority on name equality.
        for (int i = start + 16; i < end; i++) hash = (hash ^ (b[i] & 0xFF)) * 0x100000001B3L;
        int folded = (int) (hash ^ (hash >>> 32));
        folded = (folded ^ (folded >>> 16)) * 0x85EBCA6B;
        return (folded ^ (folded >>> 13)) & attributeMask;
    }

    private static boolean xmlSpace(final byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * Consumes optional whitespace and the {@code >} of an end tag,
     * returning the next position, or {@code -1} at the window limit.
     */
    final int closeAngleOr(int j) {
        while (j < n && xmlSpace(b[j])) j++;
        if (j >= n) return -1;
        if (b[j] != '>') throw fail("Malformed end tag", j);
        return j + 1;
    }

    static int lstrip(final byte[] a, int s, final int e) {
        while (s < e && (a[s] & 0xFF) <= ' ') s++;
        return s;
    }

    static int rstrip(final byte[] a, final int s, int e) {
        while (e > s && (a[e - 1] & 0xFF) <= ' ') e--;
        return e;
    }

    private static boolean allWs(final byte[] a, final int s, final int e) {
        for (int j = s; j < e; j++) {
            if ((a[j] & 0xFF) > ' ') return false;
        }
        return true;
    }

    final XmlException fail(final String message, final int offset) {
        return new XmlException(message + " (byte offset " + (base + offset) + ")");
    }
}
