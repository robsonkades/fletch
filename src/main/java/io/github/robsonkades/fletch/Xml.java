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


import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxLazyException;
import com.ctc.wstx.stax.WstxInputFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.io.Stax2ByteArraySource;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.StringReader;

/**
 * Entry point for the declarative XML streaming extraction API.
 *
 * <p>Fletch reads XML in a single forward pass over a StAX (Woodstox) stream
 * and materialises exactly the values the caller asks for — no DOM tree, no
 * reflection, no binding annotations:
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
 * contract, including the document-order requirement.
 *
 * <h2>Factory configuration</h2>
 * <p>All extractions share a single pre-configured, thread-safe Woodstox
 * factory tuned for throughput:
 * <ul>
 *   <li>{@code IS_NAMESPACE_AWARE = false} — elements are matched by their raw
 *       tag name, so namespace resolution (15-25 % of per-element work) is
 *       skipped entirely. Documents using a default namespace — a common
 *       profile for fiscal documents such as the Brazilian NF-e — match by
 *       plain local name; for prefixed elements the prefix is part of the
 *       name (e.g. {@code "soap:Body"}).</li>
 *   <li>{@code SUPPORT_DTD = false} and {@code IS_SUPPORTING_EXTERNAL_ENTITIES = false}
 *       — eliminates the XXE attack surface.</li>
 *   <li>{@code IS_COALESCING = false} — lets Woodstox split large text nodes into
 *       chunks; {@link XmlCursorImpl} handles multi-chunk assembly with a single
 *       {@code StringBuilder} allocation.</li>
 *   <li>{@code P_PRESERVE_LOCATION = false} — skips per-event line/column
 *       bookkeeping; extraction never reports locations, so this is pure win.</li>
 *   <li>{@code P_REPORT_PROLOG_WHITESPACE = false} — suppresses whitespace
 *       events outside the root element instead of dispatching them.</li>
 *   <li>Buffer size 16 KB — a cache-friendly I/O granularity for the
 *       small-to-medium documents (tens to hundreds of KB) this API targets.</li>
 *   <li>The singleton factory is thread-safe; its shared symbol table interns
 *       element names after warm-up, making subsequent parses of same-shaped
 *       documents faster.</li>
 * </ul>
 *
 * <h2>Error contract</h2>
 * <p>All parse failures surface as {@link XmlException}. Because
 * {@code P_LAZY_PARSING} is enabled, Woodstox reports errors detected while
 * finishing a deferred token as the <em>unchecked</em>
 * {@link WstxLazyException}; both it and the checked
 * {@link XMLStreamException} are translated here so callers see a single
 * exception type.
 *
 * <h2>Debug mode</h2>
 * <p>Running with assertions enabled ({@code -ea}) or with
 * {@code -Dfletch.xml.debug=true} turns on out-of-order read detection in
 * the cursor: an extractor that requests elements against document order
 * fails fast with a descriptive {@link XmlException} instead of silently
 * producing {@code null}s. Zero overhead when off — see {@link XmlCursor}.
 */
public final class Xml {

    private static final WstxInputFactory FACTORY;

    static {
        FACTORY = new WstxInputFactory();
        FACTORY.setProperty(XMLInputFactory2.P_LAZY_PARSING, true);
        FACTORY.setProperty(XMLInputFactory2.SUPPORT_DTD, false);
        FACTORY.setProperty(XMLInputFactory2.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory2.IS_COALESCING, false);
        FACTORY.setProperty(XMLInputFactory2.IS_NAMESPACE_AWARE, false);
        FACTORY.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, false);
        FACTORY.setProperty(XMLInputFactory2.P_REPORT_PROLOG_WHITESPACE, false);

        FACTORY.setProperty(WstxInputProperties.P_INPUT_BUFFER_LENGTH, 16_384);
        FACTORY.setProperty(WstxInputProperties.P_MIN_TEXT_SEGMENT, 64);
        FACTORY.setProperty(WstxInputProperties.P_MAX_TEXT_LENGTH, 1_000_000);
        FACTORY.setProperty(WstxInputProperties.P_MAX_ELEMENT_DEPTH, 200);
    }

    private Xml() {}

    /**
     * Extracts a typed result from an XML {@link InputStream} in a single
     * forward pass.
     *
     * <p>The stream is <em>not</em> closed by this method — lifecycle stays
     * with the caller. Character encoding is auto-detected from the
     * byte-order mark or the XML declaration.
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
        return extract(() -> FACTORY.createXMLStreamReader(input), extractor, "Error processing XML stream");
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
        return extract(() -> FACTORY.createXMLStreamReader(new StringReader(xml)), extractor, "Error processing XML string");
    }

    /**
     * Extracts a typed result from a raw XML byte array in a single forward
     * pass. Woodstox auto-detects the encoding from the byte-order mark or
     * the XML declaration.
     *
     * <p>Uses {@link Stax2ByteArraySource} so Woodstox bootstraps directly on
     * the array — no {@code ByteArrayInputStream} indirection and no
     * stream-read loop. This is the fastest overload when the document is
     * already in memory.
     *
     * @param <T>       the result type produced by the extractor
     * @param bytes     the encoded XML document
     * @param extractor extraction logic invoked with a cursor positioned
     *                  before the document's root element
     * @return whatever the extractor returns (possibly {@code null})
     * @throws XmlException if the document is not well-formed
     */
    public static <T> T extract(final byte[] bytes, final XmlExtractor<T> extractor) {
        return extract(() -> FACTORY.createXMLStreamReader(new Stax2ByteArraySource(bytes, 0, bytes.length)), extractor, "Error processing XML bytes");
    }

    // -------------------------------------------------------------------------
    // Shared extraction pipeline
    // -------------------------------------------------------------------------

    /** Creates a reader for one extraction pass; may fail with a stream error. */
    @FunctionalInterface
    private interface ReaderSupplier {
        XMLStreamReader create() throws XMLStreamException;
    }

    private static <T> T extract(final ReaderSupplier source, final XmlExtractor<T> extractor, final String errorMessage) {
        XMLStreamReader reader = null;
        try {
            reader = source.create();
            return extractor.extract(new XmlCursorImpl(reader));
        } catch (XMLStreamException e) {
            throw new XmlException(errorMessage, e);
        } catch (WstxLazyException e) {
            // Lazy parsing defers well-formedness errors to getText()/getTextCharacters(),
            // where Woodstox wraps them in this unchecked exception.
            throw new XmlException(errorMessage, e.getCause() != null ? e.getCause() : e);
        } finally {
            closeQuietly(reader);
        }
    }

    private static void closeQuietly(final XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {}
        }
    }
}
