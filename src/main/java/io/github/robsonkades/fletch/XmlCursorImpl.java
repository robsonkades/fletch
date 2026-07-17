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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Order-tolerant {@link XmlCursor} implemented directly over the byte-level
 * scanning engine ({@link XmlCursorEngine}).
 *
 * <h2>Order tolerance by span capture</h2>
 * <p>The document is fully buffered, so a sibling scanned past on the way to
 * a later request does not need to be materialised: it is crossed at skip
 * speed (tag balance only) and remembered as four {@code int}s — name span
 * and subtree span — in the scope's {@link #pending} array. A later request
 * for it is served by <em>re-scanning</em> those bytes through a replay
 * cursor. Reads that follow document order record nothing and never re-scan;
 * that is the flat, allocation-free fast path.
 *
 * <h2>Scope model</h2>
 * <p>A cursor reads the content of one element (its <em>scope</em>). Live
 * cursors share the engine's single forward position through a {@link Ctx};
 * a replayed child gets its own {@code Ctx} over the buffered span, so replay
 * never disturbs the live scan. The start tag's spans are kept for
 * {@link #attribute} (parsed lazily on first request) and {@link #name}.
 *
 * <p>After a live child extractor returns, the parent drains whatever the
 * extractor left unread — at skip speed — so the parent always resumes at
 * the next sibling. The scope's end tag is verified against the element name
 * (hash compare); content skipped inside unread subtrees is balance-checked
 * only.
 */
final class XmlCursorImpl implements XmlCursor {

    /** One forward scan position, shared by the chain of live cursors over it. */
    static final class Ctx {
        int pos;
        XmlCursorImpl owner;
    }

    private static final int MISS = -2;
    private static final int LIVE = -1;

    private final XmlCursorEngine eng;
    private final Ctx ctx;

    // Start-tag identity: name span (nameS < 0 marks the synthetic root
    // cursor), attribute region, and the name hash end tags are verified with.
    private final int nameS;
    private final int nameE;
    private final long nameHash;
    private final int attrS;
    private final int attrE;

    /** Skipped-sibling spans, quads of (nameS, nameE, subtreeS, subtreeE). */
    private int[] pending;
    private int pendingN;

    /** Logical exhaustion: misses and {@link #skip} stop serving requests. */
    private boolean closed;

    /** True once this element's own end tag was consumed from its context. */
    private boolean endConsumed;

    /** Root cursor only: a second top-level element is a hard error. */
    private boolean rootElementSeen;

    private String tag;

    /** Root cursor, positioned before the document element. */
    XmlCursorImpl(final XmlCursorEngine eng, final Ctx ctx) {
        this.eng = eng;
        this.ctx = ctx;
        this.nameS = -1;
        this.nameE = -1;
        this.nameHash = 0;
        this.attrS = 0;
        this.attrE = 0;
    }

    /** Element cursor, positioned just after the element's start tag. */
    private XmlCursorImpl(final XmlCursorEngine eng, final Ctx ctx, final int nameS, final int nameE,
                          final int attrS, final int attrE, final boolean selfClosed) {
        this.eng = eng;
        this.ctx = ctx;
        this.nameS = nameS;
        this.nameE = nameE;
        this.nameHash = Swar.hash(eng.b, nameS, nameE - nameS);
        this.attrS = attrS;
        this.attrE = attrE;
        if (selfClosed) {
            this.closed = true;
            this.endConsumed = true;
        }
    }

    // -------------------------------------------------------------------------
    // XmlCursor interface
    // -------------------------------------------------------------------------

    @Override
    public <T> T child(final String name, final XmlExtractor<T> extractor) {
        final int q = locate(name, null);
        if (q == MISS) return null;
        if (q == LIVE) {
            final XmlCursorImpl child = new XmlCursorImpl(eng, ctx,
                    eng.hitS, eng.hitE, eng.hitE, eng.hitGt, eng.hitSelfClose);
            ctx.owner = child;
            final T result = extractor.extract(child);
            drain(child);
            return result;
        }
        return extractor.extract(replayCursor(q));
    }

    @Override
    public <T> List<T> children(final String name, final XmlExtractor<T> extractor) {
        final List<T> results = new ArrayList<>();
        for (int q = 0; q < pendingN; ) {
            if (pendingMatches(q, name, null)) {
                results.add(extractor.extract(replayCursor(q)));
            } else {
                q++;
            }
        }
        while (true) {
            final int q = liveLocate(name, null);
            if (q == MISS) break;
            final XmlCursorImpl child = new XmlCursorImpl(eng, ctx,
                    eng.hitS, eng.hitE, eng.hitE, eng.hitGt, eng.hitSelfClose);
            ctx.owner = child;
            results.add(extractor.extract(child));
            drain(child);
        }
        return results;
    }

    @Override
    public <T> T value(final String name, final Class<T> type) {
        return readValue(locate(name, null), type);
    }

    @Override
    public <T> T firstOf(final Class<T> type, final String... names) {
        return readValue(locate(null, names), type);
    }

    @Override
    public <T> T attribute(final String name, final Class<T> type) {
        if (nameS < 0) return null;
        final byte[] b = eng.b;
        int j = attrS;
        while (true) {
            while (j < attrE && (b[j] & 0xFF) <= ' ') j++;
            if (j >= attrE || b[j] == '/') return null;
            final int as = j;
            while (j < attrE) {
                final int c = b[j] & 0xFF;
                if (c == '=' || c <= ' ') break;
                j++;
            }
            final int ae = j;
            if (ae == as) throw eng.fail("Malformed attribute", as);
            while (j < attrE && (b[j] & 0xFF) <= ' ') j++;
            if (j >= attrE || b[j] != '=') throw eng.fail("Malformed attribute", as);
            j++;
            while (j < attrE && (b[j] & 0xFF) <= ' ') j++;
            if (j >= attrE) throw eng.fail("Malformed attribute", as);
            final int q = b[j] & 0xFF;
            if (q != '"' && q != '\'') throw eng.fail("Unquoted attribute value", j);
            final int vs = ++j;
            final int ve = Swar.memchr(b, j, attrE, q);
            if (ve < 0) throw eng.fail("Unterminated attribute value", vs);
            j = ve + 1;
            if (nameEq(name, as, ae)) {
                if (vs >= ve) return null;
                if (eng.attrDirty(vs, ve)) {
                    eng.cookAttrValue(vs, ve);
                    return TypeConverter.convert(eng.cook, 0, eng.cookLen, type);
                }
                return TypeConverter.convert(b, vs, ve, type);
            }
        }
    }

    @Override
    public String name() {
        if (nameS < 0) return null;
        if (tag == null) {
            tag = new String(eng.b, nameS, nameE - nameS, StandardCharsets.UTF_8);
        }
        return tag;
    }

    @Override
    public void skip() {
        pending = null;
        pendingN = 0;
        closed = true;
    }

    // -------------------------------------------------------------------------
    // Child location: buffered spans first, then the live scan
    // -------------------------------------------------------------------------

    /**
     * Finds the next direct child matching {@code one} (or any of
     * {@code any}). Returns a pending-quad index, {@link #LIVE} with the hit
     * stashed on the engine, or {@link #MISS}.
     */
    private int locate(final String one, final String[] any) {
        for (int q = 0; q < pendingN; q++) {
            if (pendingMatches(q, one, any)) return q;
        }
        return liveLocate(one, any);
    }

    /**
     * Scans forward in this scope for the next matching direct child,
     * recording every non-matching sibling crossed as a pending span. Ends
     * the scope — and consumes its end tag — on a miss.
     */
    private int liveLocate(final String one, final String[] any) {
        if (closed) return MISS;
        if (ctx.owner != this) {
            throw new XmlException("Cursor used out of scope: a nested extraction is still active");
        }
        final byte[] b = eng.b;
        int i = ctx.pos;
        while (true) {
            final int lt = Swar.memchr(b, i, eng.n, '<');
            if (lt < 0) {
                if (nameS >= 0) throw eng.fail("Unexpected end of document", eng.n);
                if (!rootElementSeen) throw eng.fail("No root element found", 0);
                ctx.pos = eng.n;
                closed = true;
                endConsumed = true;
                return MISS;
            }
            if (lt + 2 > eng.n) throw eng.fail("Unexpected end of document", lt);
            final int c = b[lt + 1] & 0xFF;
            if (c == '/') {
                if (nameS < 0) throw eng.fail("Unexpected end tag", lt);
                final int s = lt + 2;
                final int e = eng.nameEnd(s);
                final int nx = eng.closeAngleOr(e);
                if (nx < 0) throw eng.fail("Malformed end tag", lt);
                if (Swar.hash(b, s, e - s) != nameHash) throw eng.fail("Mismatched end tag", lt);
                ctx.pos = nx;
                closed = true;
                endConsumed = true;
                return MISS;
            }
            if (c == '?') {
                i = eng.skipPi(lt);
                continue;
            }
            if (c == '!') {
                i = eng.bang(lt);
                continue;
            }
            final int s = lt + 1;
            final int e = eng.nameEnd(s);
            if (e == s) throw eng.fail("Invalid markup", lt);
            final int gt = eng.tagEndOr(e);
            if (gt < 0) throw eng.fail("Unterminated start tag", lt);
            final boolean selfClose = b[gt - 1] == '/';
            if (nameS < 0) {
                if (rootElementSeen) throw eng.fail("Multiple root elements", lt);
                rootElementSeen = true;
            }
            if (matches(one, any, s, e)) {
                eng.hitS = s;
                eng.hitE = e;
                eng.hitGt = gt;
                eng.hitSelfClose = selfClose;
                ctx.pos = gt + 1;
                return LIVE;
            }
            final int subEnd = selfClose ? gt + 1 : eng.skipSubtree(gt + 1, 0);
            addPending(s, e, subEnd);
            ctx.pos = subEnd;
            i = subEnd;
        }
    }

    /**
     * Consumes whatever a live child's extractor left unread — at skip speed
     * — so this cursor resumes at the child's next sibling.
     */
    private void drain(final XmlCursorImpl child) {
        ctx.owner = this;
        if (child.endConsumed) return;
        child.closed = true;
        final byte[] b = eng.b;
        int i = ctx.pos;
        while (true) {
            final int lt = Swar.memchr(b, i, eng.n, '<');
            if (lt < 0 || lt + 2 > eng.n) throw eng.fail("Unexpected end of document", eng.n);
            final int c = b[lt + 1] & 0xFF;
            if (c == '/') {
                final int s = lt + 2;
                final int e = eng.nameEnd(s);
                final int nx = eng.closeAngleOr(e);
                if (nx < 0) throw eng.fail("Malformed end tag", lt);
                if (Swar.hash(b, s, e - s) != child.nameHash) throw eng.fail("Mismatched end tag", lt);
                child.endConsumed = true;
                ctx.pos = nx;
                return;
            }
            if (c == '?') {
                i = eng.skipPi(lt);
            } else if (c == '!') {
                i = eng.bang(lt);
            } else {
                final int e = eng.nameEnd(lt + 1);
                if (e == lt + 1) throw eng.fail("Invalid markup", lt);
                final int gt = eng.tagEndOr(e);
                if (gt < 0) throw eng.fail("Unterminated start tag", lt);
                i = b[gt - 1] == '/' ? gt + 1 : eng.skipSubtree(gt + 1, 0);
            }
        }
    }

    /** Builds a cursor replaying a buffered sibling, on its own context. */
    private XmlCursorImpl replayCursor(final int q) {
        final int subS = pending[(q << 2) + 2];
        removePending(q);
        final int s = subS + 1;
        final int e = eng.nameEnd(s);
        final int gt = eng.tagEndOr(e);
        final boolean selfClose = eng.b[gt - 1] == '/';
        final Ctx replay = new Ctx();
        replay.pos = gt + 1;
        final XmlCursorImpl cur = new XmlCursorImpl(eng, replay, s, e, e, gt, selfClose);
        replay.owner = cur;
        return cur;
    }

    // -------------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------------

    private <T> T readValue(final int q, final Class<T> type) {
        if (q == MISS) return null;
        if (q == LIVE) {
            if (eng.hitSelfClose) return null;
            ctx.pos = eng.readLeafText(Swar.hash(eng.b, eng.hitS, eng.hitE - eng.hitS), ctx.pos);
            return convert(type);
        }
        final int ns = pending[q << 2];
        final int ne = pending[(q << 2) + 1];
        removePending(q);
        final int gt = eng.tagEndOr(ne);
        if (eng.b[gt - 1] == '/') return null;
        eng.readLeafText(Swar.hash(eng.b, ns, ne - ns), gt + 1);
        return convert(type);
    }

    private <T> T convert(final Class<T> type) {
        return TypeConverter.convert(eng.valA, eng.valS, eng.valE, type);
    }

    // -------------------------------------------------------------------------
    // Pending spans and name matching
    // -------------------------------------------------------------------------

    private void addPending(final int nameStart, final int nameEnd, final int subtreeEnd) {
        if (pending == null) {
            pending = new int[16];
        } else if (pendingN << 2 == pending.length) {
            pending = Arrays.copyOf(pending, pending.length << 1);
        }
        final int at = pendingN << 2;
        pending[at] = nameStart;
        pending[at + 1] = nameEnd;
        pending[at + 2] = nameStart - 1;   // the '<' of the start tag
        pending[at + 3] = subtreeEnd;
        pendingN++;
    }

    private void removePending(final int q) {
        System.arraycopy(pending, (q + 1) << 2, pending, q << 2, (pendingN - q - 1) << 2);
        pendingN--;
    }

    private boolean pendingMatches(final int q, final String one, final String[] any) {
        return matches(one, any, pending[q << 2], pending[(q << 2) + 1]);
    }

    private boolean matches(final String one, final String[] any, final int s, final int e) {
        if (one != null) return nameEq(one, s, e);
        for (final String name : any) {
            if (nameEq(name, s, e)) return true;
        }
        return false;
    }

    /**
     * Compares a requested name against raw tag bytes without allocating:
     * ASCII names — the overwhelming case — compare char-to-byte; anything
     * else falls back to a UTF-8 encode of the requested name.
     */
    private boolean nameEq(final String want, final int s, final int e) {
        final byte[] b = eng.b;
        final int len = e - s;
        final int chars = want.length();
        if (chars == len) {
            int j = 0;
            while (j < len) {
                final char c = want.charAt(j);
                if (c >= 0x80) break;
                if (c != (b[s + j] & 0xFF)) return false;
                j++;
            }
            if (j == len) return true;
        } else {
            boolean ascii = true;
            for (int j = 0; j < chars; j++) {
                if (want.charAt(j) >= 0x80) {
                    ascii = false;
                    break;
                }
            }
            if (ascii) return false;
        }
        final byte[] w = want.getBytes(StandardCharsets.UTF_8);
        return w.length == len && Arrays.equals(w, 0, len, b, s, e);
    }
}
