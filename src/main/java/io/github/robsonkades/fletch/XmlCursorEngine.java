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
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The engine behind the {@link XmlCursor} API: one reusable scanning context
 * per extraction, driven pull-style by {@link XmlCursorImpl}.
 *
 * <p>The whole document is buffered before scanning — the caller's
 * {@code byte[]} in place, a {@code String} encoded once, an
 * {@link InputStream} drained into the engine's reusable buffer — so every
 * position a cursor records (skipped-sibling spans, start-tag spans) stays
 * valid for the document's lifetime. That is what makes order tolerance
 * cheap: a sibling scanned past is remembered as four {@code int}s and
 * re-scanned from the buffer if a later request asks for it, instead of
 * being materialised into event objects.
 *
 * <p>Engines are pooled by {@link Xml}; an engine serves one extraction at a
 * time and is not thread-safe.
 */
final class XmlCursorEngine extends ByteScanner {

    /** Pool hygiene: buffers above this size are dropped instead of retained. */
    private static final int RETAIN = 1 << 20;

    // Start-tag scratch of the last live match found by a cursor scan:
    // name span [hitS, hitE), tag end at hitGt. Consumed immediately.
    int hitS;
    int hitE;
    int hitGt;
    boolean hitSelfClose;

    XmlCursorEngine() {
        super(DEFAULT_WINDOW);
    }

    <T> T extract(final byte[] bytes, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(bytes, "bytes");
        prepare(bytes, bytes.length, true);
        return go(extractor);
    }

    <T> T extract(final String xml, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(xml, "xml");
        final byte[] u = xml.getBytes(StandardCharsets.UTF_8);
        prepare(u, u.length, false);
        return go(extractor);
    }

    <T> T extract(final InputStream input, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(input, "input");
        if (io == null) {
            io = new byte[window];
        }
        b = io;
        n = 0;
        base = 0;
        eof = false;
        src = input;
        drainFully();
        prepare(b, n, true);
        return go(extractor);
    }

    private <T> T go(final XmlExtractor<T> extractor) {
        try {
            final XmlCursorImpl.Ctx ctx = new XmlCursorImpl.Ctx();
            ctx.pos = scanFrom;
            final XmlCursorImpl root = new XmlCursorImpl(this, ctx);
            ctx.owner = root;
            return extractor.extract(root);
        } finally {
            releaseSource();
        }
    }

    /** Drops document-sized scratch before the engine returns to the pool. */
    void trimForReuse() {
        if (io != null && io.length > RETAIN) io = null;
        if (trans != null && trans.length > RETAIN) trans = null;
        if (cook.length > RETAIN) cook = new byte[256];
    }
}
