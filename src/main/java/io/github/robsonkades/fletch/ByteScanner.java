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

    final int window;

    // Verify end tags inside skipped subtrees instead of only counting them.
    boolean strictSkip;
    private long[] skipStack = new long[16];

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
            b = Arrays.copyOf(b, b.length << 1);
            io = b;
        }
        try {
            final int r = src.read(b, n, b.length - n);
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
                if (n == b.length) {
                    if (b.length >= MAX_ARRAY) {
                        throw fail("Document exceeds the maximum supported size of 2 GiB", 0);
                    }
                    b = Arrays.copyOf(b, (int) Math.min(MAX_ARRAY, b.length * 2L));
                    io = b;
                }
                final int r = src.read(b, n, b.length - n);
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

    /**
     * Points the scanner at a streaming source, resetting window state. The
     * caller decides how to consume it ({@link #drainFully} or incremental
     * {@link #more} refills).
     */
    final void beginStream(final InputStream stream) {
        if (io == null) {
            io = new byte[window];
        }
        b = io;
        n = 0;
        base = 0;
        eof = false;
        src = stream;
    }

    /**
     * Drops every reference to caller-supplied data — the document array (or
     * view) and the stream. Engines call it when an extraction ends, and
     * again before parking in a pool, so a failed extraction can never leak
     * the caller's document or stream through pooled scratch state.
     */
    final void releaseSource() {
        src = null;
        b = null;
        valA = null;
    }

    // ------------------------------------------------------------------ encoding detection

    final void prepare(final byte[] doc, final int len, final boolean sniff) {
        src = null;
        eof = false;
        base = 0;
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
     * tag, verifying the end tag against {@code endTag} — the SWAR hash of
     * the element's name. The value lands in {@link #valA}{@code [}{@link
     * #valS}{@code , }{@link #valE}{@code )}, already trimmed; an empty span
     * means the element was blank. Returns the index just past the element's
     * end tag. The dominant single-run case touches the bytes once and
     * produces a zero-copy span (anchored in the window across refills);
     * entities, CR normalization, CDATA and nested markup take the cooked
     * path.
     */
    final int readLeafText(final long endTag, int start) {
        // One fused scan finds the '<' and detects dirtiness ('&' entity or
        // '\r' normalization) on the way, so clean values — the dominant
        // case — are touched once. The scan resumes where it left off after
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
            final int resume = lt >= 0 ? lt : n;
            final int sh = more(start);           // keep the value span alive
            if (sh < 0) throw fail("Unexpected end of document", n);
            start -= sh;
            scan = resume - sh;
        }
        if (b[lt + 1] == '/') {
            int s, e, ret;
            while (true) {
                s = lt + 2;
                e = nameEnd(s);
                if (e < n) {
                    ret = closeAngleOr(e);
                    if (ret >= 0) break;
                }
                final int sh = more(start);       // keep the value span alive too
                if (sh < 0) throw fail("Malformed end tag", lt);
                start -= sh;
                lt -= sh;
            }
            if (Swar.hash(b, s, e - s) != endTag) throw fail("Mismatched end tag", lt);
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
        return readLeafMixed(endTag, start, lt);
    }

    /** Rare leaf shapes: CDATA sections, comments, PIs or child elements. */
    private int readLeafMixed(final long endTag, final int start, final int firstLt) {
        cookLen = 0;
        int mark = cookLen;
        boolean nonWs = appendPiece(start, firstLt);
        endRun(mark, nonWs);
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
                    if (Swar.hash(b, s, e - s) != endTag) throw fail("Mismatched end tag", i);
                    setCookedTrimmed();
                    return nx;
                }
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
                if (b[gt - 1] != '/') d++;
                i = gt + 1;
            }
            // text run up to the next markup, streamed in safe pieces
            mark = cookLen;
            nonWs = false;
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
        final int from = cookLen;
        decodeText(s, e);
        return !allWs(cook, from, cookLen);
    }

    private void endRun(final int mark, final boolean nonWs) {
        if (!nonWs) cookLen = mark;
    }

    /**
     * Picks a piece boundary that never splits an entity reference or a
     * {@code \r\n} pair across a window refill.
     */
    private int safeCut(final int s, final int e) {
        if (s >= e) return e;
        int cut = e;
        if (b[cut - 1] == '\r') cut--;
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
                    || (cp >= 0xD800 && cp <= 0xDFFF)) {
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
        if (cook.length < min) cook = Arrays.copyOf(cook, Math.max(min, cook.length << 1));
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
     * every end tag is instead verified against the name hash of the start
     * tag it closes, {@code outerTag} standing in for the enclosing element
     * whose start tag the caller already consumed. {@code outerTag} is
     * ignored when strict skipping is off.
     */
    final int skipSubtree(int i, final long outerTag) {
        int d = 1;
        if (strictSkip) {
            skipStack[0] = outerTag;
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
                if (strictSkip) {
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
                    if (Swar.hash(b, s, e - s) != skipStack[--d]) throw fail("Mismatched end tag", i);
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
                int gt;
                while ((gt = tagEndOr(i + 1)) < 0) {
                    final int sh = more(i);
                    if (sh < 0) throw fail("Unterminated start tag", i);
                    i -= sh;
                }
                if (b[gt - 1] != '/') {
                    if (strictSkip) {
                        final int s = i + 1;
                        final int e = nameEnd(s);
                        if (d == skipStack.length) skipStack = Arrays.copyOf(skipStack, d << 1);
                        skipStack[d] = Swar.hash(b, s, e - s);
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
        int j = Math.min(i + 6, n);
        while (true) {
            final int gt = Swar.memchr(b, j, n, '>');
            if (gt >= 0) {
                if (gt >= 2 && b[gt - 1] == '-' && b[gt - 2] == '-') return gt + 1;
                j = gt + 1;
                continue;
            }
            final int keep = Math.max(j, n - 2);
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
            final int c = b[j] & 0xFF;
            if (c == '>' || c == '/' || c == '=' || c <= ' ') break;
            j++;
        }
        return j;
    }

    /**
     * Quote-aware scan for the {@code >} closing a start tag ({@code >} is
     * legal inside attribute values). Returns {@code -1} when the tag
     * extends past the window — the caller anchors the tag and refills.
     * Deliberately a byte loop: real attribute regions are shorter than the
     * word-scan break-even (measured -4..7% end-to-end when this ran on
     * {@code memchr3}), and the attribute-less tag exits on the first byte.
     */
    final int tagEndOr(int j) {
        while (true) {
            if (j >= n) return -1;
            final int c = b[j] & 0xFF;
            if (c == '>') return j;
            if (c == '"' || c == '\'') {
                final int q = Swar.memchr(b, j + 1, n, c);
                if (q < 0) return -1;
                j = q + 1;
            } else {
                j++;
            }
        }
    }

    /**
     * Consumes optional whitespace and the {@code >} of an end tag,
     * returning the next position, or {@code -1} at the window limit.
     */
    final int closeAngleOr(int j) {
        while (j < n && (b[j] & 0xFF) <= ' ') j++;
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
