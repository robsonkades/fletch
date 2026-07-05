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

import java.util.List;

/**
 * Declarative, forward-only XML cursor for streaming extraction.
 *
 * <p><b>Contract</b>: when an {@link XmlExtractor} is invoked, the cursor
 * is positioned at the {@code START_ELEMENT} of the element to extract.
 * All navigation methods advance the underlying reader and return typed
 * results — no raw event loop is ever exposed to the caller.
 *
 * <h2>Name matching</h2>
 * <p>Parsing is not namespace-aware: elements are matched by their raw tag
 * name. Documents with a default (unprefixed) namespace match by plain local
 * name; for prefixed elements the prefix is part of the name to match
 * (e.g. {@code cursor.child("soap:Body", ...)}).
 *
 * <h2>Ordering</h2>
 * <p>Requests must be issued in the same order that the corresponding
 * elements appear in the source XML. This is a forward-only streaming
 * parser — elements cannot be re-read. Asking for an element the cursor has
 * already passed reports absence ({@code null} / empty list).
 *
 * <p>In <em>debug mode</em> that silent absence becomes an
 * {@link XmlException} naming the out-of-order element, so misordered
 * extractors fail fast during development instead of producing partial data.
 * Debug mode is on when assertions are enabled ({@code -ea} — the Surefire
 * default, so tests get it automatically) or with
 * {@code -Dfletch.xml.debug=true}; it costs nothing in production.
 *
 * <h2>Scope exhaustion</h2>
 * <p>A scan that reaches the end of the enclosing element without a match
 * necessarily consumes that element's {@code END_ELEMENT}; the same happens
 * after {@link #children} (which sweeps the whole scope) and {@link #skip}.
 * From that point on, every request in the current extractor reports absence
 * — the cursor never leaks into the parent scope. Optional trailing elements
 * therefore behave as expected: a miss on one simply yields {@code null} for
 * it and for anything requested after it.
 *
 * <h2>Thread safety</h2>
 * <p>A cursor instance is bound to one parse call and must not be shared
 * across threads. {@link XmlExtractor} constants are safe to share.
 */
public interface XmlCursor {

    /**
     * Navigates to the first direct child element with the given name and
     * extracts its content using the supplied extractor.
     *
     * <p>Returns {@code null} when the element is not found before the end
     * of the enclosing container. Non-matching siblings are skipped
     * efficiently without allocating intermediate objects.
     *
     * <p>After a miss the enclosing scope has been fully consumed; subsequent
     * requests in the same extractor also report absence (see
     * <em>Scope exhaustion</em> in the class docs).
     *
     * @param <T>       the result type produced by the extractor
     * @param name      tag name of the direct child element to navigate to
     * @param extractor extraction logic invoked with the cursor positioned at
     *                  the child's {@code START_ELEMENT}
     * @return the extractor's result, or {@code null} when the element is absent
     * @throws XmlException on stream errors, or in debug mode when the element
     *                      was already consumed by an earlier request
     */
    <T> T child(String name, XmlExtractor<T> extractor);

    /**
     * Collects all direct child elements with the given name, applying the
     * extractor to each. Non-matching siblings are skipped.
     *
     * <p>Returns an empty (mutable) list when no matching children exist.
     *
     * <p>Collecting requires sweeping the entire enclosing scope, so this is
     * naturally the <em>last</em> read of a scope: anything requested after
     * it reports absence.
     *
     * @param <T>       the element type produced by the extractor
     * @param name      tag name of the direct child elements to collect
     * @param extractor extraction logic invoked once per matching child
     * @return a mutable list with one entry per match, in document order;
     *         empty when there are none
     * @throws XmlException on stream errors, or in debug mode when the elements
     *                      were already consumed by an earlier request
     */
    <T> List<T> children(String name, XmlExtractor<T> extractor);

    /**
     * Reads the text content of the first direct child element with the given
     * name, converting it to the requested type.
     *
     * <p>Returns {@code null} when the element is absent or its text is empty
     * — including for {@code String}. Supported types: {@code String},
     * {@code Integer}, {@code Long}, {@code BigDecimal}, {@code Double},
     * {@code Boolean} ({@code true}/{@code false}/{@code 1}/{@code 0}),
     * {@code Instant} (ISO-8601), and any {@code Enum} (matched by constant
     * name).
     *
     * <p>Surrounding whitespace is trimmed; CDATA sections and text split
     * across multiple parser chunks are assembled transparently.
     *
     * @param <T>  the target type
     * @param name tag name of the direct child element to read
     * @param type the class to convert the text to
     * @return the converted value, or {@code null} when the element is absent
     *         or empty
     * @throws XmlException on stream errors, for unsupported target types, or
     *                      in debug mode when the element was already consumed
     */
    <T> T value(String name, Class<T> type);

    /**
     * Reads the text of the first direct child whose tag name matches any of
     * the given alternatives. Useful for {@code xsd:choice} groups such as
     * {@code CPF | CNPJ | idEstrangeiro}.
     *
     * <p>The scan stops at the first matching element in <em>document
     * order</em> (the order of {@code names} does not establish priority);
     * remaining alternatives are never visited. Returns {@code null} when
     * none of the alternatives is present.
     *
     * @param <T>   the target type (same conversions as {@link #value})
     * @param type  the class to convert the text to
     * @param names tag names accepted as alternatives
     * @return the converted value of the first alternative found, or
     *         {@code null} when none is present
     * @throws XmlException on stream errors, for unsupported target types, or
     *                      in debug mode when an alternative was already consumed
     */
    <T> T firstOf(Class<T> type, String... names);

    /**
     * Reads an attribute of the current element without advancing the reader.
     *
     * <p>Must be called <em>before</em> any child navigation — attributes
     * belong to the {@code START_ELEMENT} the cursor is positioned at, and
     * child scans move past it. Returns {@code null} when the attribute is
     * absent or empty.
     *
     * @param <T>  the target type (same conversions as {@link #value})
     * @param name the attribute name
     * @param type the class to convert the attribute text to
     * @return the converted value, or {@code null} when the attribute is
     *         absent or empty
     * @throws XmlException when called after child navigation has moved the
     *                      cursor off the element's {@code START_ELEMENT}
     */
    <T> T attribute(String name, Class<T> type);

    /**
     * Returns the tag name of the element the cursor is positioned at.
     *
     * @return the current element's tag name
     */
    String name();

    /**
     * Skips the current element and all its descendants, positioning the
     * reader after the matching {@code END_ELEMENT}. The element's scope is
     * consumed: subsequent requests report absence.
     *
     * @throws XmlException on stream errors
     */
    void skip();
}
