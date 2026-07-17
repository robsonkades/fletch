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

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * A reusable extraction engine: the runtime of an {@link XmlMapping}.
 *
 * <p>The engine executes one fused loop over the document bytes —
 * tokenizer, path matcher and value extraction in a single pass with no
 * intermediate event objects. Elements outside the mapping are crossed by a
 * depth-counting skip that only looks for markup, and values are decoded
 * lazily through the {@link XmlValue} flyweight handed to bindings.
 *
 * <p>All scratch state (state stack, draft stack, cook buffer, window
 * buffer) belongs to the engine and is reused across documents, so
 * steady-state extraction allocates only what the caller's bindings and
 * finisher create. Obtain engines from {@link XmlMapping#newEngine()} and
 * keep one per worker; a engine must not be used from more than one thread
 * at a time.
 *
 * <h2>Streaming</h2>
 * <p>{@code byte[]} and {@code String} input is scanned in place.
 * {@link InputStream} input is <em>streamed</em> through a reusable sliding
 * window (64&nbsp;KB by default): scanned content is discarded as the window
 * refills, so memory stays bounded by the largest single token — a tag, one
 * text value, a CDATA section or a comment — rather than by the document.
 * The window grows on demand for oversized tokens up to a 16&nbsp;MiB cap.
 *
 * <h2>Well-formedness checking</h2>
 * <p>On the traversed path (elements the mapping matches) end-tag names are
 * verified and attribute syntax is enforced; errors carry the byte offset in
 * the source. Inside skipped subtrees only tag balance is checked — names
 * are not compared. {@code <!DOCTYPE} is rejected outright; only the five
 * predefined entities and character references are recognised.
 *
 * <h2>Encodings</h2>
 * <p>UTF-8 and US-ASCII are scanned natively. ISO-8859-1 (detected from the
 * XML declaration) and UTF-16 (detected from the byte-order mark or a
 * {@code 3C 00}/{@code 00 3C} prefix) are transcoded once — for streams this
 * falls back to reading the document fully. Other encodings are rejected
 * with a clear error.
 *
 * @param <T> the mapping's result type
 */
final class XmlMappingEngine<T> extends ByteScanner {

    private final XmlMapping<T> mapping;

    // Matched-container state stack; stack[0] is the synthetic document state.
    private int[] stack = new int[32];

    // Draft stack: drafts[0] is the document draft, deeper entries are open groups.
    private Object[] drafts = new Object[8];
    private int draftTop;

    private long bound;
    private boolean done;

    // Canonicalization cache for XmlValue.asCanonical(): open-addressed,
    // bounded, lazily allocated so mappings that never canonicalize pay nothing.
    private static final int CANON_SIZE = 1024;
    private static final int CANON_MAX_LEN = 64;
    private static final int CANON_PROBES = 8;
    private long[] canonHash;
    private byte[][] canonBytes;
    private String[] canonVal;

    private final Val val = new Val();

    XmlMappingEngine(final XmlMapping<T> mapping) {
        this(mapping, DEFAULT_WINDOW);
    }

    /** Test hook: a tiny window forces the refill machinery to run constantly. */
    XmlMappingEngine(final XmlMapping<T> mapping, final int window) {
        super(window);
        this.mapping = mapping;
        this.strictSkip = mapping.strictSkip;
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Extracts from a raw XML document. Encoding is detected from the
     * byte-order mark or the XML declaration; UTF-8 input is scanned in
     * place with zero copying.
     *
     * @param xml the encoded document
     * @return the mapping's result
     * @throws XmlException if the document is malformed
     */
    public T extract(final byte[] xml) {
        Objects.requireNonNull(xml, "xml");
        prepare(xml, xml.length, true);
        return go();
    }

    /**
     * Extracts from an XML string. The text is encoded to UTF-8 once; any
     * encoding pseudo-attribute in the XML declaration is ignored, as the
     * content is already decoded.
     *
     * @param xml the document text
     * @return the mapping's result
     * @throws XmlException if the document is malformed
     */
    public T extract(final String xml) {
        Objects.requireNonNull(xml, "xml");
        final byte[] u = xml.getBytes(StandardCharsets.UTF_8);
        prepare(u, u.length, false);
        return go();
    }

    /**
     * Extracts from a stream, processing it through the engine's sliding
     * window: memory stays bounded by the largest single token instead of
     * the document size. The stream is <em>not</em> closed — lifecycle stays
     * with the caller. ISO-8859-1 and UTF-16 streams are read fully and
     * transcoded, as their scan is not incremental.
     *
     * @param xml the stream to read; consumed but not closed
     * @return the mapping's result
     * @throws XmlException if the document is malformed or reading fails
     */
    public T extract(final InputStream xml) {
        Objects.requireNonNull(xml, "xml");
        if (io == null) {
            io = new byte[window];
        }
        b = io;
        n = 0;
        base = 0;
        eof = false;
        src = xml;
        while (n < 512 && more(0) >= 0) {
            // fill enough of the window to sniff the prolog
        }
        sniffStream();
        return go();
    }

    // ------------------------------------------------------------------ fused scan loop

    private T go() {
        try {
            return run(scanFrom);
        } finally {
            releaseSource();
            Arrays.fill(drafts, null);
        }
    }

    private T run(int i) {
        done = false;
        bound = 0;
        draftTop = 0;
        drafts[0] = mapping.rootDraft.get();
        stack[0] = 0;
        int sp = 0;
        boolean rootSeen = false;

        while (true) {
            int lt = Swar.memchr(b, i, n, '<');
            while (lt < 0) {
                final int sh = more(n);           // scanned content is dead here
                if (sh < 0) {
                    if (sp != 0) throw fail("Unexpected end of document", n);
                    if (!rootSeen) throw fail("No root element found", 0);
                    return mapping.finisher.apply(drafts[0]);
                }
                i = 0;
                lt = Swar.memchr(b, i, n, '<');
            }
            i = lt;
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
                final int st = stack[sp];
                if (sp == 0 || Swar.hash(b, s, e - s) != mapping.stateTag[st]) {
                    throw fail("Mismatched end tag", i);
                }
                final int g = mapping.stateGroup[st];
                if (g >= 0) commitGroup(g);
                sp--;
                if (sp == 0) break;
                i = nx;
            } else if (c == '?') {
                i = skipPi(i);
            } else if (c == '!') {
                i = bang(i);
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
                if (sp == 0) rootSeen = true;
                final long h = Swar.hash(b, s, e - s);
                final int t = mapping.transition(stack[sp], h, b, s, e - s);
                final boolean selfClose = b[gt - 1] == '/';
                if (t < 0) {
                    i = selfClose ? gt + 1 : skipSubtree(gt + 1, h);
                } else {
                    final int g = mapping.stateGroup[t];
                    if (g >= 0) beginGroup(g);
                    if (mapping.attrCount[t] != 0) bindAttrs(t, e, gt);
                    if (selfClose) {
                        if (g >= 0) commitGroup(g);
                        i = gt + 1;
                    } else if (mapping.stateText[t] >= 0) {
                        i = leaf(t, mapping.stateText[t], gt + 1);
                    } else {
                        if (++sp == stack.length) stack = Arrays.copyOf(stack, sp << 1);
                        stack[sp] = t;
                        i = gt + 1;
                    }
                }
            }
            if (done) break;
        }
        return mapping.finisher.apply(drafts[0]);
    }

    // ------------------------------------------------------------------ groups and binding

    private void beginGroup(final int g) {
        if (++draftTop == drafts.length) drafts = Arrays.copyOf(drafts, draftTop << 1);
        drafts[draftTop] = mapping.groupDraft[g].get();
        bound &= ~mapping.groupReset[g];
    }

    private void commitGroup(final int g) {
        final Object child = drafts[draftTop];
        drafts[draftTop--] = null;
        mapping.groupCommit[g].accept(drafts[draftTop], child);
    }

    private void bind(final int f, final byte[] arr, final int s, final int e) {
        final long bit = 1L << f;
        if ((mapping.onceMask & bit) != 0 && (bound & bit) != 0) return;
        val.set(arr, s, e);
        mapping.bindings[f].bind(drafts[draftTop], val);
        bound |= bit;
        final long req = mapping.requiredMask;
        if (req != 0 && (bound & req) == req && draftTop == 0) done = true;
    }

    /**
     * Reads a matched leaf element's text through the shared scanner and
     * binds it when non-blank, returning the index just past the end tag.
     */
    private int leaf(final int t, final int f, final int start) {
        final int nx = readLeafText(mapping.stateTag[t], start);
        if (valS < valE) bind(f, valA, valS, valE);
        return nx;
    }

    // ------------------------------------------------------------------ attributes

    /**
     * Parses the attribute region of a start tag whose state declared
     * attribute targets, binding matches. The whole tag is already in the
     * window. Stops as soon as every declared attribute has been seen.
     */
    private void bindAttrs(final int t, final int from, final int gt) {
        final int aBase = mapping.attrBase[t];
        final int cnt = mapping.attrCount[t];
        int remaining = cnt;
        int j = from;
        while (remaining > 0) {
            while (j < gt && (b[j] & 0xFF) <= ' ') j++;
            if (j >= gt || b[j] == '/') return;
            final int as = j;
            while (j < gt) {
                final int c = b[j] & 0xFF;
                if (c == '=' || c <= ' ') break;
                j++;
            }
            final int ae = j;
            if (ae == as) throw fail("Malformed attribute", as);
            while (j < gt && (b[j] & 0xFF) <= ' ') j++;
            if (j >= gt || b[j] != '=') throw fail("Malformed attribute", as);
            j++;
            while (j < gt && (b[j] & 0xFF) <= ' ') j++;
            if (j >= gt) throw fail("Malformed attribute", as);
            final int q = b[j] & 0xFF;
            if (q != '"' && q != '\'') throw fail("Unquoted attribute value", j);
            final int vs = ++j;
            final int ve = Swar.memchr(b, j, gt, q);
            if (ve < 0) throw fail("Unterminated attribute value", vs);
            j = ve + 1;
            final long h = Swar.hash(b, as, ae - as);
            for (int k = aBase; k < aBase + cnt; k++) {
                if (mapping.attrHash[k] == h && mapping.attrNameLen[k] == ae - as
                        && Arrays.equals(mapping.blob, mapping.attrNameOff[k], mapping.attrNameOff[k] + (ae - as),
                                b, as, ae)) {
                    if (ve > vs) bindAttrValue(mapping.attrField[k], vs, ve);
                    remaining--;
                    break;
                }
            }
        }
    }

    private void bindAttrValue(final int f, final int vs, final int ve) {
        if (attrDirty(vs, ve)) {
            cookAttrValue(vs, ve);
            bind(f, cook, 0, cookLen);
        } else {
            bind(f, b, vs, ve);
        }
    }

    /**
     * Returns a {@code String} for {@code a[s, e)}, deduplicated through the
     * engine cache. Hash hits verify the stored bytes, so a collision costs
     * a probe, never a wrong value; a full probe window or an oversized
     * value falls back to a fresh {@code String}.
     */
    private String canonical(final byte[] a, final int s, final int e) {
        final int len = e - s;
        if (len > CANON_MAX_LEN) {
            return new String(a, s, len, StandardCharsets.UTF_8);
        }
        if (canonVal == null) {
            canonHash = new long[CANON_SIZE];
            canonBytes = new byte[CANON_SIZE][];
            canonVal = new String[CANON_SIZE];
        }
        final long h = Swar.hash(a, s, len);
        int idx = (int) h & (CANON_SIZE - 1);
        for (int probe = 0; probe < CANON_PROBES; probe++) {
            final String hit = canonVal[idx];
            if (hit == null) {
                canonHash[idx] = h;
                canonBytes[idx] = Arrays.copyOfRange(a, s, e);
                canonVal[idx] = new String(a, s, len, StandardCharsets.UTF_8);
                return canonVal[idx];
            }
            if (canonHash[idx] == h && canonBytes[idx].length == len
                    && Arrays.equals(canonBytes[idx], 0, len, a, s, e)) {
                return hit;
            }
            idx = (idx + 1) & (CANON_SIZE - 1);
        }
        return new String(a, s, len, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ value flyweight

    private final class Val implements XmlValue {

        private byte[] a;
        private int s;
        private int e;

        void set(final byte[] arr, final int start, final int end) {
            this.a = arr;
            this.s = start;
            this.e = end;
        }

        @Override
        public String asString() {
            return new String(a, s, e - s, StandardCharsets.UTF_8);
        }

        @Override
        public String asCanonical() {
            return canonical(a, s, e);
        }

        @Override
        public long asLong() {
            return TypeConverter.parseLong(a, s, e);
        }

        @Override
        public int asInt() {
            return TypeConverter.parseInt(a, s, e);
        }

        @Override
        public double asDouble() {
            return Double.parseDouble(asString());
        }

        @Override
        public BigDecimal asDecimal() {
            return TypeConverter.parseDecimal(a, s, e);
        }

        @Override
        public boolean asBoolean() {
            return TypeConverter.parseBoolean(a, s, e);
        }

        @Override
        public Instant asInstant() {
            return TypeConverter.parseInstant(a, s, e);
        }

        @Override
        public <E extends Enum<E>> E asEnum(final Class<E> type) {
            return Enum.valueOf(type, asString());
        }
    }
}
