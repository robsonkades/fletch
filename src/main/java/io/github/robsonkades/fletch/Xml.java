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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * Entry point for the declarative XML streaming extraction API.
 *
 * <p>Fletch reads XML in a single forward pass of its own byte-level
 * scanning engine and materialises exactly the values the caller asks for —
 * no DOM tree, no reflection, no binding annotations:
 *
 * <pre>{@code
 * record Book(String title, Integer year) {}
 *
 * static final XmlExtractor<Book> BOOK = book -> new Book(
 *         book.value("title", String.class),
 *         book.value("year", Integer.class));
 *
 * Book book = Xml.extract(xml, doc -> doc.child("book", BOOK));
 * }</pre>
 *
 * <p>When the extractor passed to an {@code extract} method is invoked, the
 * cursor is positioned <em>before</em> the document's root element, so the
 * first navigation call addresses the root itself ({@code doc.child("book",
 * ...)} in the example above). See {@link XmlCursor} for the full navigation
 * contract, including its order-tolerant reads.
 *
 * <p>The same engine also drives a declarative, push-style alternative: build
 * an {@link XmlMapping} with {@link #mapping} — declaring wanted values up front as
 * paths — and run it with {@link #extract(byte[], XmlMapping)}. Both styles are
 * order-independent single passes; the cursor reads by navigating, the mapping by
 * binding declared paths into a draft.
 *
 * <h2>The engine</h2>
 * <p>Extraction runs on a fused byte-level scan: tokenizer, name matching
 * and value decoding execute in one loop over the document bytes, with no
 * per-event objects. Subtrees the extractor never asks about are crossed by
 * a balance-counting skip at SWAR scan speed, and a sibling scanned past on
 * the way to a later request is remembered as a byte span — four
 * {@code int}s — and re-scanned only if the extractor asks for it. When the
 * root extractor returns, reading stops: trailing content is never scanned.
 * <ul>
 *   <li><b>Name matching.</b> Parsing is not namespace-aware: elements are
 *       matched by their raw tag name. Documents using a default namespace —
 *       a common profile for fiscal documents such as the Brazilian NF-e —
 *       match by plain local name; for prefixed elements the prefix is part
 *       of the name (e.g. {@code "soap:Body"}).</li>
 *   <li><b>Security.</b> {@code <!DOCTYPE} is rejected at its first byte —
 *       there is no DTD processing and no XXE surface. Only the five
 *       predefined entities and numeric character references are decoded.</li>
 *   <li><b>Encodings.</b> UTF-8 and US-ASCII are scanned in place. ISO-8859-1
 *       (declared in the XML declaration) and UTF-16 (detected from the
 *       byte-order mark or a {@code 3C 00}/{@code 00 3C} prefix) are
 *       transcoded once; other encodings are rejected.</li>
 *   <li><b>Limits.</b> A single text value is capped at 16&nbsp;MiB.</li>
 *   <li><b>Well-formedness.</b> Elements the extractor traverses get their
 *       end tags verified and attribute syntax enforced; skipped subtrees
 *       are checked for tag balance only.</li>
 * </ul>
 *
 * <h2>Error contract</h2>
 * <p>All parse failures surface as {@link XmlException} carrying the byte
 * offset of the failure in the source. I/O failures while reading an
 * {@link InputStream} preserve the underlying {@code IOException} as the
 * cause. Value conversion failures propagate from the conversion itself
 * (e.g. {@link NumberFormatException}), exactly as documented on
 * {@link XmlCursor#value}.
 */
public final class Xml {

    /**
     * Reusable engines, striped by thread id. An engine holds only scratch
     * buffers, so a stale slot costs one fresh allocation, never corruption;
     * nested/reentrant extractions simply take a fresh engine.
     */
    private static final AtomicReferenceArray<XmlCursorEngine> POOL = new AtomicReferenceArray<>(8);

    private Xml() {}

    /**
     * Extracts a typed result from an XML {@link InputStream} in a single
     * forward pass.
     *
     * <p>The stream is read fully into the engine's reusable buffer and is
     * <em>not</em> closed — lifecycle stays with the caller. Character
     * encoding is auto-detected from the byte-order mark or the XML
     * declaration.
     *
     * @param <T>       the result type produced by the extractor
     * @param input     the stream to read; consumed but not closed
     * @param extractor extraction logic invoked with a cursor positioned
     *                  before the document's root element
     * @return whatever the extractor returns (possibly {@code null})
     * @throws XmlException if the document is not well-formed or an I/O error
     *                      occurs while reading
     */
    public static <T> T extract(final InputStream input, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(extractor, "extractor");
        final XmlCursorEngine engine = take();
        try {
            return engine.extract(input, extractor);
        } finally {
            release(engine);
        }
    }

    /**
     * Extracts a typed result from an XML {@code String} in a single forward
     * pass.
     *
     * <p>Prefer the {@code byte[]} or {@link InputStream} overloads when
     * reading from files or the network to avoid an intermediate
     * {@code String} allocation.
     *
     * @param <T>       the result type produced by the extractor
     * @param xml       the XML document text
     * @param extractor extraction logic invoked with a cursor positioned
     *                  before the document's root element
     * @return whatever the extractor returns (possibly {@code null})
     * @throws XmlException if the document is not well-formed
     */
    public static <T> T extract(final String xml, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(extractor, "extractor");
        final XmlCursorEngine engine = take();
        try {
            return engine.extract(xml, extractor);
        } finally {
            release(engine);
        }
    }

    /**
     * Extracts a typed result from a raw XML byte array in a single forward
     * pass. Encoding is auto-detected from the byte-order mark or the XML
     * declaration; UTF-8 documents are scanned in place with zero copying,
     * making this the fastest overload when the document is already in
     * memory.
     *
     * @param <T>       the result type produced by the extractor
     * @param bytes     the encoded XML document
     * @param extractor extraction logic invoked with a cursor positioned
     *                  before the document's root element
     * @return whatever the extractor returns (possibly {@code null})
     * @throws XmlException if the document is not well-formed
     */
    public static <T> T extract(final byte[] bytes, final XmlExtractor<T> extractor) {
        Objects.requireNonNull(extractor, "extractor");
        final XmlCursorEngine engine = take();
        try {
            return engine.extract(bytes, extractor);
        } finally {
            release(engine);
        }
    }

    /**
     * Starts a declarative extraction {@link XmlMapping mapping} — the push-style
     * counterpart to the cursor. A mapping declares every wanted value up front
     * as a path and fills a mutable draft in a single order-independent pass;
     * compile it once as a {@code static final} constant and run it with
     * {@link #extract(byte[], XmlMapping)}.
     *
     * @param <D>           the draft type accumulated while reading a document
     * @param draftSupplier creates one fresh draft per extracted document
     * @return a builder to declare paths on
     */
    public static <D> XmlMapping.Builder<D> mapping(final Supplier<D> draftSupplier) {
        return XmlMapping.builder(draftSupplier);
    }

    /**
     * Runs a compiled {@link XmlMapping} against a raw XML byte array. Encoding is
     * auto-detected from the byte-order mark or the XML declaration; UTF-8
     * documents are scanned in place with zero copying.
     *
     * @param <T>   the result type produced by the mapping's finisher
     * @param bytes the encoded XML document
     * @param mapping  the compiled extraction mapping
     * @return the finisher's result
     * @throws XmlException if the document is not well-formed
     */
    public static <T> T extract(final byte[] bytes, final XmlMapping<T> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return mapping.extract(bytes);
    }

    /**
     * Runs a compiled {@link XmlMapping} against an XML {@code String}. Prefer the
     * {@code byte[]} or {@link InputStream} overloads when reading from files
     * or the network to avoid an intermediate {@code String} allocation.
     *
     * @param <T>  the result type produced by the mapping's finisher
     * @param xml  the XML document text
     * @param mapping the compiled extraction mapping
     * @return the finisher's result
     * @throws XmlException if the document is not well-formed
     */
    public static <T> T extract(final String xml, final XmlMapping<T> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return mapping.extract(xml);
    }

    /**
     * Runs a compiled {@link XmlMapping} against an XML {@link InputStream}.
     * A UTF-8 or US-ASCII stream is processed through the mapping's sliding
     * window, so memory stays bounded by the largest single token rather than
     * the document size — this is the only entry point in the library that
     * does not hold the whole document. ISO-8859-1 and UTF-16 streams are read
     * fully and transcoded first, as their scan is not incremental. The stream
     * is <em>not</em> closed — lifecycle stays with the caller.
     *
     * @param <T>   the result type produced by the mapping's finisher
     * @param input the stream to read; consumed but not closed
     * @param mapping  the compiled extraction mapping
     * @return the finisher's result
     * @throws XmlException if the document is not well-formed or an I/O error
     *                      occurs while reading
     */
    public static <T> T extract(final InputStream input, final XmlMapping<T> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return mapping.extract(input);
    }

    private static XmlCursorEngine take() {
        final int slot = (int) Thread.currentThread().getId() & 7;
        final XmlCursorEngine engine = POOL.getAndSet(slot, null);
        return engine != null ? engine : new XmlCursorEngine();
    }

    private static void release(final XmlCursorEngine engine) {
        engine.trimForReuse();
        POOL.set((int) Thread.currentThread().getId() & 7, engine);
    }
}
