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
 * Fast, allocation-minimal type converter for XML text values.
 *
 * <p>Backs {@link XmlCursor#value}, {@link XmlCursor#firstOf} and
 * {@link XmlCursor#attribute}. Absent or empty text uniformly converts to
 * {@code null} — including for {@code String} — per the {@link XmlCursor}
 * contract.
 *
 * <p><b>Performance notes</b>:
 * <ul>
 *   <li>Class identity comparison ({@code ==}) is used instead of
 *       {@code .equals()} so the JIT can constant-fold the branch for
 *       monomorphic call sites.</li>
 *   <li>{@code String} fast-path is first — by far the most common target
 *       type in document extraction.</li>
 *   <li>{@code Integer} and {@code Long} use their static {@code valueOf} so
 *       the JVM cache (-128..127) avoids boxing for small numeric codes.</li>
 *   <li>No {@code HashMap} lookup — the if-chain is branch-predictor-friendly
 *       given that String and Integer dominate typical leaf values.</li>
 * </ul>
 */
final class TypeConverter {

    private TypeConverter() {}

    /**
     * Converts raw XML text to the requested type.
     *
     * @param <T>   the target type
     * @param value the raw text; {@code null} and {@code ""} convert to {@code null}
     * @param type  one of {@code String}, {@code Integer}, {@code Long},
     *              {@code BigDecimal}, {@code Double}, {@code Boolean},
     *              {@code Instant}, or any {@code Enum} class
     * @return the converted value, or {@code null} for absent/empty text
     * @throws XmlException for unsupported target types or invalid boolean text
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T convert(final String value, final Class<T> type) {
        // Absent or empty text is uniformly null — this is the XmlCursor.value()
        // contract ("null when the element is absent or its text is empty"),
        // including for String.
        if (value == null || value.isEmpty()) return null;
        // String fast-path: identity check avoids String.equals() entirely
        if (type == String.class) return (T) value;

        if (type == Integer.class) return (T) Integer.valueOf(Integer.parseInt(value));
        if (type == Long.class)    return (T) Long.valueOf(Long.parseLong(value));
        if (type == BigDecimal.class) return (T) new BigDecimal(value);
        if (type == Double.class)  return (T) Double.valueOf(Double.parseDouble(value));
        if (type == Boolean.class) return (T) parseBoolean(value);
        if (type == Instant.class) return (T) Instant.parse(value);

        // Enum: last resort — rare in extraction hot paths
        if (type.isEnum()) return (T) Enum.valueOf((Class<Enum>) type, value);

        throw new XmlException("Unsupported target type for conversion: " + type.getName());
    }

    private static Boolean parseBoolean(final String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return Boolean.FALSE;
        throw new XmlException("Invalid boolean value: " + value);
    }
}
