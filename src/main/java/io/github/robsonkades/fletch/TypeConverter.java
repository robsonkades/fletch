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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Fast, allocation-minimal conversion of raw XML value bytes to Java types.
 *
 * <p>Backs both extraction styles: the cursor's {@link XmlCursor#value},
 * {@link XmlCursor#firstOf} and {@link XmlCursor#attribute} convert through
 * {@link #convert}, and the mapping engine's {@link XmlValue} accessors
 * delegate to the same parsers. Numeric, boolean and temporal targets are
 * parsed directly from the value bytes where a byte parser exists. Floating
 * point, additional temporal types, big integers, UUIDs and enums use the JDK's
 * text parsers; uncommon decimal and instant forms also delegate to the JDK.
 *
 * <p>An empty span uniformly converts to {@code null} — including for
 * {@code String} — per the {@link XmlCursor} contract.
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

    private static final int[] POW10 = {
            1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000
    };

    private TypeConverter() {
    }

    /**
     * Converts the value bytes {@code a[s, e)} to the requested type.
     *
     * @param <T>  the target type
     * @param a    the buffer holding the value bytes
     * @param s    the start of the value span (inclusive)
     * @param e    the end of the value span (exclusive); an empty span
     *             converts to {@code null}
     * @param type a target supported by {@link XmlCursor#value(String, Class)}
     * @return the converted value, or {@code null} for an empty span
     * @throws XmlException for unsupported target types or invalid boolean text
     */
    @SuppressWarnings("unchecked")
    static <T> T convert(final byte[] a, final int s, final int e, final Class<T> type) {
        if (s >= e) return null;
        if (type == String.class) return (T) str(a, s, e);

        if (type == Integer.class) return (T) Integer.valueOf(parseInt(a, s, e));
        if (type == Long.class) return (T) Long.valueOf(parseLong(a, s, e));
        if (type == BigDecimal.class) return (T) parseDecimal(a, s, e);
        if (type == Double.class) return (T) Double.valueOf(Double.parseDouble(str(a, s, e)));
        if (type == Boolean.class) return (T) (parseBoolean(a, s, e) ? Boolean.TRUE : Boolean.FALSE);
        if (type == Instant.class) return (T) parseInstant(a, s, e);
        if (type == Byte.class || type == byte.class) return (T) Byte.valueOf(toByte(parseInt(a, s, e)));
        if (type == Short.class || type == short.class) return (T) Short.valueOf(toShort(parseInt(a, s, e)));
        if (type == int.class) return (T) Integer.valueOf(parseInt(a, s, e));
        if (type == long.class) return (T) Long.valueOf(parseLong(a, s, e));
        if (type == double.class) return (T) Double.valueOf(Double.parseDouble(str(a, s, e)));
        if (type == boolean.class) return (T) Boolean.valueOf(parseBoolean(a, s, e));

        return convertText(str(a, s, e), type);
    }

    /** Default-method bridge: preserves existing XmlValue implementations and byte accessors. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T convert(final XmlValue value, final Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type == String.class) return (T) value.asString();
        if (type == Integer.class || type == int.class) return (T) Integer.valueOf(value.asInt());
        if (type == Long.class || type == long.class) return (T) Long.valueOf(value.asLong());
        if (type == BigDecimal.class) return (T) value.asDecimal();
        if (type == Double.class || type == double.class) return (T) Double.valueOf(value.asDouble());
        if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(value.asBoolean());
        if (type == Instant.class) return (T) value.asInstant();
        if (type == Byte.class || type == byte.class) return (T) Byte.valueOf(toByte(value.asInt()));
        if (type == Short.class || type == short.class) return (T) Short.valueOf(toShort(value.asInt()));
        if (type.isEnum()) return (T) value.asEnum((Class) type);
        return convertText(value.asString(), type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T convertText(final String text, final Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type == Float.class || type == float.class) return (T) Float.valueOf(text);
        if (type == BigInteger.class) return (T) new BigInteger(text);
        if (type == Character.class || type == char.class) {
            if (text.length() != 1 || Character.isSurrogate(text.charAt(0))) {
                throw new IllegalArgumentException("Expected one non-surrogate UTF-16 character: " + text);
            }
            return (T) Character.valueOf(text.charAt(0));
        }
        if (type == LocalDate.class) return (T) LocalDate.parse(text);
        if (type == LocalTime.class) return (T) LocalTime.parse(text);
        if (type == LocalDateTime.class) return (T) LocalDateTime.parse(text);
        if (type == OffsetTime.class) return (T) OffsetTime.parse(text);
        if (type == OffsetDateTime.class) return (T) OffsetDateTime.parse(text);
        if (type == ZonedDateTime.class) return (T) ZonedDateTime.parse(text);
        if (type == Duration.class) return (T) Duration.parse(text);
        if (type == Period.class) return (T) Period.parse(text);
        if (type == UUID.class) return (T) UUID.fromString(text);
        if (type.isEnum()) return (T) Enum.valueOf((Class<Enum>) type, text);
        throw new XmlException("Unsupported target type for conversion: " + type.getName());
    }

    private static byte toByte(final int value) {
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw new NumberFormatException("Value out of range for byte: " + value);
        }
        return (byte) value;
    }

    private static short toShort(final int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new NumberFormatException("Value out of range for short: " + value);
        }
        return (short) value;
    }

    /**
     * Parses a signed decimal {@code long} from the bytes. Magnitudes beyond
     * eighteen digits (and an empty magnitude) fall back to the JDK parser
     * for exact overflow semantics and error messages.
     */
    static long parseLong(final byte[] a, final int s, final int e) {
        int i = s;
        boolean neg = false;
        final int c0 = a[i] & 0xFF;
        if (c0 == '-' || c0 == '+') {
            neg = c0 == '-';
            i++;
        }
        if (i == e || e - i > 18) {
            return Long.parseLong(str(a, s, e));
        }
        long v = 0;
        for (; i < e; i++) {
            final int d = (a[i] & 0xFF) - '0';
            if (d < 0 || d > 9) throw new NumberFormatException("For input string: \"" + str(a, s, e) + "\"");
            v = v * 10 + d;
        }
        return neg ? -v : v;
    }

    static int parseInt(final byte[] a, final int s, final int e) {
        final long v = parseLong(a, s, e);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new NumberFormatException("For input string: \"" + str(a, s, e) + "\"");
        }
        return (int) v;
    }

    /**
     * Parses a {@link BigDecimal}. Plain decimal notation with up to eighteen
     * significant digits is built without any intermediate {@code String} or
     * {@code BigInteger}; longer or exponent forms fall back to the exact
     * {@code BigDecimal} constructor.
     */
    static BigDecimal parseDecimal(final byte[] a, final int s, final int e) {
        int i = s;
        boolean neg = false;
        final int c0 = a[i] & 0xFF;
        if (c0 == '-' || c0 == '+') {
            neg = c0 == '-';
            i++;
        }
        long unscaled = 0;
        int scale = 0;
        int digits = 0;
        boolean dot = false;
        for (; i < e; i++) {
            final int c = a[i] & 0xFF;
            if (c == '.') {
                if (dot) return new BigDecimal(str(a, s, e));
                dot = true;
                continue;
            }
            final int d = c - '0';
            if (d < 0 || d > 9 || digits == 18) {
                return new BigDecimal(str(a, s, e));
            }
            unscaled = unscaled * 10 + d;
            digits++;
            if (dot) scale++;
        }
        if (digits == 0) {
            return new BigDecimal(str(a, s, e));
        }
        return BigDecimal.valueOf(neg ? -unscaled : unscaled, scale);
    }

    /**
     * Parses a boolean: {@code true}/{@code false} (case-insensitive) or
     * {@code 1}/{@code 0}.
     *
     * @throws XmlException if the text is not a recognised boolean
     */
    static boolean parseBoolean(final byte[] a, final int s, final int e) {
        final int len = e - s;
        if (len == 1) {
            final int c = a[s] & 0xFF;
            if (c == '1') return true;
            if (c == '0') return false;
        } else if (len == 4 && ci(a, s, 't') && ci(a, s + 1, 'r') && ci(a, s + 2, 'u') && ci(a, s + 3, 'e')) {
            return true;
        } else if (len == 5 && ci(a, s, 'f') && ci(a, s + 1, 'a') && ci(a, s + 2, 'l')
                && ci(a, s + 3, 's') && ci(a, s + 4, 'e')) {
            return false;
        }
        throw new XmlException("Invalid boolean value: " + str(a, s, e));
    }

    private static boolean ci(final byte[] a, final int i, final char lower) {
        return ((a[i] & 0xFF) | 0x20) == lower;
    }

    /**
     * Parses an ISO-8601 {@link Instant}. The common layouts are parsed
     * arithmetically from the bytes; anything else delegates to
     * {@link Instant#parse} for an authoritative result or error.
     */
    static Instant parseInstant(final byte[] a, final int s, final int e) {
        final Instant fast = tryInstant(a, s, e);
        return fast != null ? fast : Instant.parse(str(a, s, e));
    }

    /**
     * Arithmetic parse of the common ISO-8601 layouts; null routes to Instant.parse.
     */
    private static Instant tryInstant(final byte[] a, final int s, final int e) {
        final int len = e - s;
        if (len < 20) return null;
        if (a[s + 4] != '-' || a[s + 7] != '-' || (a[s + 10] != 'T' && a[s + 10] != 't')
                || a[s + 13] != ':' || a[s + 16] != ':') {
            return null;
        }
        final int year = d4(a, s);
        final int month = d2(a, s + 5);
        final int day = d2(a, s + 8);
        final int hh = d2(a, s + 11);
        final int mm = d2(a, s + 14);
        final int ss = d2(a, s + 17);
        if ((year | month | day | hh | mm | ss) < 0
                || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)
                || hh > 23 || mm > 59 || ss > 59) {
            return null;
        }
        int p = s + 19;
        int nanos = 0;
        if (a[p] == '.') {
            p++;
            int f = 0;
            int digs = 0;
            while (p < e) {
                final int d = (a[p] & 0xFF) - '0';
                if (d < 0 || d > 9) break;
                if (digs == 9) return null;
                f = f * 10 + d;
                digs++;
                p++;
            }
            if (digs == 0) return null;
            nanos = f * POW10[9 - digs];
        }
        if (p >= e) return null;
        final int cz = a[p] & 0xFF;
        long offset = 0;
        if (cz == 'Z' || cz == 'z') {
            if (p + 1 != e) return null;
        } else if (cz == '+' || cz == '-') {
            if (p + 6 != e || a[p + 3] != ':') return null;
            final int oh = d2(a, p + 1);
            final int om = d2(a, p + 4);
            if (oh < 0 || om < 0 || oh > 18 || om > 59 || (oh == 18 && om != 0)) return null;
            offset = (oh * 3600L + om * 60L) * (cz == '-' ? -1 : 1);
        } else {
            return null;
        }
        final long days = daysFromCivil(year, month, day);
        return Instant.ofEpochSecond(days * 86400L + hh * 3600L + mm * 60L + ss - offset, nanos);
    }

    private static int d2(final byte[] a, final int i) {
        final int h = (a[i] & 0xFF) - '0';
        final int l = (a[i + 1] & 0xFF) - '0';
        if (h < 0 || h > 9 || l < 0 || l > 9) return -1;
        return h * 10 + l;
    }

    private static int d4(final byte[] a, final int i) {
        final int h = d2(a, i);
        final int l = d2(a, i + 2);
        if (h < 0 || l < 0) return -1;
        return h * 100 + l;
    }

    private static int daysInMonth(final int y, final int m) {
        if (m == 2) {
            final boolean leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
            return leap ? 29 : 28;
        }
        return (m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31;
    }

    /**
     * Howard Hinnant's days-from-civil algorithm (proleptic Gregorian).
     */
    private static long daysFromCivil(int y, final int m, final int d) {
        y -= m <= 2 ? 1 : 0;
        final long era = Math.floorDiv(y, 400);
        final int yoe = (int) (y - era * 400);
        final int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        final int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static String str(final byte[] a, final int s, final int e) {
        return new String(a, s, e - s, StandardCharsets.UTF_8);
    }
}
