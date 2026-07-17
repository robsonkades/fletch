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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A lazily-decoded XML text or attribute value handed to an {@link XmlBinding}.
 *
 * <p>The instance is a <em>flyweight view</em> over the parse buffer owned by
 * the {@link XmlMappingEngine}: no {@code String} or boxed object exists until an
 * accessor that needs one is called, and the numeric/temporal accessors parse
 * the underlying bytes directly without materialising text at all.
 *
 * <p><b>Lifecycle</b>: the value is only valid for the duration of the
 * {@link XmlBinding#bind} call that receives it. Bindings must extract what
 * they need immediately (typically into the draft object); retaining the
 * {@code XmlValue} itself is a programming error.
 *
 * <p><b>Content contract</b>: bindings are only invoked for values that are
 * non-empty after the same whitespace handling the cursor API applies —
 * element text is trimmed and blank text never reaches a binding, so every
 * accessor operates on at least one byte. Absent or blank elements simply
 * leave the draft untouched, preserving the {@code null}-for-absent
 * convention of {@link XmlCursor}.
 *
 * <p>Conversion failures propagate exactly like the cursor API's converters:
 * malformed numbers throw {@link NumberFormatException}, malformed instants
 * throw {@link java.time.format.DateTimeParseException}, unknown enum
 * constants throw {@link IllegalArgumentException}, and invalid booleans
 * throw {@link XmlException}.
 */
public interface XmlValue {

    /**
     * Decodes the value as a {@code String}. This accessor always allocates;
     * prefer the typed accessors for numeric targets and
     * {@link #asCanonical()} for low-cardinality codes.
     *
     * @return the decoded text, never empty
     */
    String asString();

    /**
     * Decodes the value as a {@code String}, deduplicated through the
     * engine's canonicalization cache: repeated occurrences of the same
     * bytes return the same instance for the lifetime of the engine.
     *
     * <p>Intended for low-cardinality fields — state codes, units, currency
     * and status codes — where it removes recurring {@code String} churn
     * across large document batches. The cache is bounded (values up to 64
     * bytes, a fixed number of slots); anything beyond it falls back to a
     * fresh {@code String}, so correctness never depends on cache hits.
     *
     * @return the decoded text, canonicalized per engine
     */
    String asCanonical();

    /**
     * Parses the value as a signed decimal {@code long} directly from the
     * underlying bytes.
     *
     * @return the parsed value
     * @throws NumberFormatException if the text is not a valid long
     */
    long asLong();

    /**
     * Parses the value as a signed decimal {@code int} directly from the
     * underlying bytes.
     *
     * @return the parsed value
     * @throws NumberFormatException if the text is not a valid int
     */
    int asInt();

    /**
     * Parses the value as a {@code double}.
     *
     * @return the parsed value
     * @throws NumberFormatException if the text is not a valid double
     */
    double asDouble();

    /**
     * Parses the value as a {@link BigDecimal}. Plain decimal notation with up
     * to eighteen significant digits is built without any intermediate
     * {@code String} or {@code BigInteger}; longer or exponent forms fall back
     * to the exact {@code BigDecimal} constructor.
     *
     * @return the parsed value
     * @throws NumberFormatException if the text is not a valid decimal
     */
    BigDecimal asDecimal();

    /**
     * Parses the value as a boolean: {@code true}/{@code false}
     * (case-insensitive) or {@code 1}/{@code 0} — the same alphabet the
     * cursor API accepts.
     *
     * @return the parsed value
     * @throws XmlException if the text is not a recognised boolean
     */
    boolean asBoolean();

    /**
     * Parses the value as an ISO-8601 {@link Instant}. The common layouts
     * ({@code 2024-06-20T11:27:22Z}, offset and fractional-second variants)
     * are parsed arithmetically from the bytes; anything else delegates to
     * {@link Instant#parse} for an authoritative result or error.
     *
     * @return the parsed instant
     * @throws java.time.format.DateTimeParseException if the text is not a
     *         valid instant
     */
    Instant asInstant();

    /**
     * Matches the value against an enum's constant names.
     *
     * @param <E>  the enum type
     * @param type the enum class
     * @return the matching constant
     * @throws IllegalArgumentException if no constant matches
     */
    <E extends Enum<E>> E asEnum(Class<E> type);
}
