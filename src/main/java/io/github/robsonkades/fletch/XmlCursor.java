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
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Declarative, order-tolerant XML cursor for streaming extraction.
 *
 * <p><b>Contract</b>: when an {@link XmlExtractor} is invoked, the cursor
 * is positioned at the start tag of the element to extract. All navigation
 * methods may advance the byte-level scan and return typed results — no raw
 * event loop is ever exposed to the caller. {@link #exists} preserves the
 * matching occurrence for a subsequent read.
 *
 * <h2>Name matching</h2>
 * <p>Parsing is not namespace-aware: elements are matched by their raw tag
 * name. Documents with a default (unprefixed) namespace match by plain local
 * name; for prefixed elements the prefix is part of the name to match
 * (e.g. {@code cursor.child("soap:Body", ...)}).
 *
 * <h2>Ordering</h2>
 * <p>Requests may address the direct children of an element in any order,
 * regardless of the order they appear in the source XML. It stays a single
 * forward pass and buffers only what it must: a read that follows document
 * order is served straight from the stream, while a read targeting a child
 * that appears later causes the children scanned past to be buffered, so a
 * later request for one of them is still answered. Each element is served at
 * most once — requesting the same name again returns the next occurrence, or
 * {@code null} when there is none.
 *
 * <h2>Misses and scope</h2>
 * <p>An absent element yields {@code null} (or an empty list for
 * {@link #children}) in any position. A request that finds no match has
 * scanned to the end of the enclosing element, buffering any non-matching
 * children it passed, so later requests for those still succeed.
 * {@link #skip} discards the rest of the current element's content: after it,
 * every request reports absence. The cursor never leaks into the parent scope.
 *
 * <h2>Thread safety</h2>
 * <p>A cursor instance is bound to one parse call and must not be shared
 * across threads. {@link XmlExtractor} constants can be shared when their
 * callbacks, including custom converters, are safe to invoke concurrently.
 */
public interface XmlCursor {

    /**
     * Checks whether a remaining direct child has the given raw tag name,
     * without consuming that occurrence. Empty and blank elements count as
     * present. Repeated checks keep returning true until a read consumes the
     * occurrence; later reads still serve siblings in document order.
     *
     * <p>The check may scan ahead and remember preceding sibling spans, subject to the
     * extraction's resource limits and ordinary skipped-subtree validation.
     * It stops at the matched start tag without traversing that subtree or
     * decoding its value, and does not certify its full well-formedness.
     * Only this cursor's direct children are searched. After {@link #skip},
     * no child remains available.
     *
     * <p>Cursors provided by {@link Xml#extract(String, XmlExtractor)} support
     * this operation. External implementations must override it: the default
     * throws {@link UnsupportedOperationException} without reading anything,
     * preserving compatibility with existing implementations.
     *
     * @param name non-null direct child tag name
     * @return whether an unread matching child exists, even if empty
     * @throws NullPointerException if name is null
     * @throws XmlException if scanning fails, a resource limit is exceeded or the cursor is out of scope
     * @throws UnsupportedOperationException if an external implementation does not support probing
     */
    default boolean exists(final String name) {
        Objects.requireNonNull(name, "name");
        throw new UnsupportedOperationException("This cursor does not support non-consuming existence checks");
    }

    /**
     * Navigates to the first direct child element with the given name and
     * extracts its content using the supplied extractor.
     *
     * <p>Returns {@code null} when no such child exists in the enclosing
     * container. Non-matching siblings are skipped without allocating
     * intermediate objects; a sibling scanned past to reach a later request is
     * buffered so it can still be read afterwards.
     *
     * @param <T>       the result type produced by the extractor
     * @param name      tag name of the direct child element to navigate to
     * @param extractor extraction logic invoked with the cursor positioned at
     *                  the child's start tag
     * @return the extractor's result, or {@code null} when the element is absent
     * @throws XmlException on stream errors
     */
    <T> T child(String name, XmlExtractor<T> extractor);

    /**
     * Collects all direct child elements with the given name, applying the
     * extractor to each. Non-matching siblings are skipped.
     *
     * <p>Returns an empty (mutable) list when no matching children exist.
     * Matches are returned in document order. Non-matching siblings are skipped
     * (and buffered), so a later request for one of them still succeeds.
     *
     * @param <T>       the element type produced by the extractor
     * @param name      tag name of the direct child elements to collect
     * @param extractor extraction logic invoked once per matching child
     * @return a mutable list with one entry per match, in document order;
     *         empty when there are none
     * @throws XmlException on stream errors
     */
    <T> List<T> children(String name, XmlExtractor<T> extractor);

    /**
     * Reads the text content of the first direct child element with the given
     * name, converting it to the requested type.
     *
     * <p>Returns {@code null} when the element is absent or its text is empty
     * — including for {@code String}. Supported types: {@code String},
     * {@code Byte}, {@code Short}, {@code Integer}, {@code Long},
     * {@code Float}, {@code Double}, {@code BigInteger}, {@code BigDecimal},
     * {@code Boolean} ({@code true}/{@code false}/{@code 1}/{@code 0}),
     * {@code Character} (one non-surrogate UTF-16 code unit), {@code UUID},
     * {@code Instant}, {@code LocalDate}, {@code LocalTime}, {@code LocalDateTime},
     * {@code OffsetTime}, {@code OffsetDateTime}, {@code ZonedDateTime},
     * {@code Duration}, {@code Period} (the corresponding JDK ISO parsers),
     * and any {@code Enum} (matched by constant name). Primitive class tokens
     * are aliases for their wrappers; absence still returns null, so unboxing
     * an absent result throws {@link NullPointerException}. Floating-point
     * conversion follows the JDK, including NaN, infinities and overflow.
     *
     * <p>Surrounding whitespace is trimmed; CDATA sections, entity references
     * and text interleaved with child elements are assembled transparently.
     *
     * @param <T>  the target type
     * @param name tag name of the direct child element to read
     * @param type the class to convert the text to
     * @return the converted value, or {@code null} when the element is absent
     *         or empty
     * @throws XmlException on stream errors or for unsupported target types
     */
    <T> T value(String name, Class<T> type);

    /**
     * Reads a child's text with an application conversion. The function receives
     * the same decoded, trimmed text as {@code value(name, String.class)}. It is
     * not called for an absent, empty or blank element. The function may return
     * null and any exception propagates unchanged. Navigation retains the usual
     * ordering and one-occurrence-per-read contract, even if conversion fails.
     *
     * @param <T> result type
     * @param name direct child tag name
     * @param converter non-null conversion, for example {@code UUID::fromString}
     * @return converted result, or null when absent or empty
     * @throws NullPointerException if converter is null, before navigating
     * @throws XmlException if parsing or a resource limit fails
     */
    default <T> T valueWith(final String name, final Function<? super String, ? extends T> converter) {
        Objects.requireNonNull(converter, "converter");
        final String text = value(name, String.class);
        return text == null ? null : converter.apply(text);
    }

    /**
     * Converts available child text or computes a fallback when the selected
     * element is absent, empty or blank. The supplier is lazy: exactly one of
     * converter or fallback runs, once. A converter returning null returns null
     * directly. XML errors and exceptions from either function propagate
     * unchanged, and do not cause another branch to run.
     *
     * <pre>{@code
     * LocalDate date = cursor.valueWith("date", READ_DATE, () -> defaultDate);
     * }</pre>
     *
     * @param <T> result type
     * @param name direct child tag name
     * @param converter non-null conversion of decoded, trimmed text
     * @param fallback non-null supplier, invoked only when no text is available
     * @return the selected function's result, possibly null
     * @throws NullPointerException if either function is null, before navigating
     * @throws XmlException if parsing or a resource limit fails
     */
    default <T> T valueWith(final String name, final Function<? super String, ? extends T> converter,
                           final Supplier<? extends T> fallback) {
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(fallback, "fallback");
        final String text = value(name, String.class);
        return text == null ? fallback.get() : converter.apply(text);
    }

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
     * @throws XmlException on stream errors or for unsupported target types
     */
    <T> T firstOf(Class<T> type, String... names);

    /**
     * Converts the first matching alternative in document order, using the same
     * text and null rules as {@link #valueWith}. A blank first match or a null
     * conversion result does not cause a search for another alternative.
     *
     * @param <T> result type
     * @param converter non-null application conversion
     * @param names accepted direct child names
     * @return the first match's converted result, or null when absent or empty
     * @throws NullPointerException if converter is null, before navigating
     * @throws XmlException if parsing or a resource limit fails
     */
    default <T> T firstOfWith(final Function<? super String, ? extends T> converter, final String... names) {
        Objects.requireNonNull(converter, "converter");
        final String text = firstOf(String.class, names);
        return text == null ? null : converter.apply(text);
    }

    /**
     * Reads an attribute of the current element, converting it to the requested
     * type. Attributes are snapshotted when the cursor enters the element, so
     * this may be called before or after navigating to children. Returns
     * {@code null} when the attribute is absent or empty.
     *
     * @param <T>  the target type (same conversions as {@link #value})
     * @param name the attribute name
     * @param type the class to convert the attribute text to
     * @return the converted value, or {@code null} when the attribute is
     *         absent or empty
     */
    <T> T attribute(String name, Class<T> type);

    /**
     * Converts an attribute using decoded text with XML whitespace normalization,
     * without trimming, just like {@code attribute(name, String.class)}. Missing
     * or empty attributes return null without invoking the function; whitespace
     * alone is a value. Functions may return null; exceptions propagate unchanged.
     * Attributes remain readable after child navigation.
     *
     * @param <T> result type
     * @param name attribute name
     * @param converter non-null application conversion
     * @return converted result, or null when absent or empty
     * @throws NullPointerException if converter is null
     * @throws XmlException if parsing or a resource limit fails
     */
    default <T> T attributeWith(final String name, final Function<? super String, ? extends T> converter) {
        Objects.requireNonNull(converter, "converter");
        final String text = attribute(name, String.class);
        return text == null ? null : converter.apply(text);
    }

    /**
     * Converts an attribute or lazily computes a fallback when it is absent or
     * empty. XML whitespace normalization applies without trimming, so a
     * whitespace-only attribute invokes the converter. Exactly one function
     * runs, once; null results are allowed and exceptions propagate unchanged.
     * A converter returning null or throwing does not invoke the fallback.
     *
     * @param <T> result type
     * @param name attribute name
     * @param converter non-null conversion of decoded attribute text
     * @param fallback non-null supplier used only for an absent or empty attribute
     * @return the selected function's result, possibly null
     * @throws NullPointerException if either function is null, before reading
     * @throws XmlException if parsing or a resource limit fails
     */
    default <T> T attributeWith(final String name, final Function<? super String, ? extends T> converter,
                               final Supplier<? extends T> fallback) {
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(fallback, "fallback");
        final String text = attribute(name, String.class);
        return text == null ? fallback.get() : converter.apply(text);
    }

    /**
     * Returns the tag name of the element the cursor is positioned at.
     *
     * @return the current element's tag name
     */
    String name();

    /**
     * Skips the current element and all its descendants, positioning the
     * scan after the matching end tag. The element's scope is
     * consumed: subsequent requests report absence.
     *
     * @throws XmlException on stream errors
     */
    void skip();
}
