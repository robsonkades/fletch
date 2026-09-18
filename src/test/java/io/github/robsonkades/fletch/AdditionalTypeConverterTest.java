/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AdditionalTypeConverterTest {
    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("-128", Byte.class, (byte) -128),
                Arguments.of("+127", byte.class, (byte) 127),
                Arguments.of("-32768", Short.class, (short) -32768),
                Arguments.of("32767", short.class, (short) 32767),
                Arguments.of("1.25", Float.class, 1.25f),
                Arguments.of("-0.0", float.class, -0.0f),
                Arguments.of("0x1.8p1", Float.class, 3.0f),
                Arguments.of("NaN", Float.class, Float.NaN),
                Arguments.of("Infinity", Float.class, Float.POSITIVE_INFINITY),
                Arguments.of("9999999999999999999999999999999999999999999", BigInteger.class,
                        new BigInteger("9999999999999999999999999999999999999999999")),
                Arguments.of("-123456789012345678901234567890", BigInteger.class,
                        new BigInteger("-123456789012345678901234567890")),
                Arguments.of("é", Character.class, 'é'),
                Arguments.of("X", char.class, 'X'),
                Arguments.of("2024-02-29", LocalDate.class, LocalDate.of(2024, 2, 29)),
                Arguments.of("23:59:59.123456789", LocalTime.class, LocalTime.of(23, 59, 59, 123456789)),
                Arguments.of("2026-09-18T12:34:56", LocalDateTime.class,
                        LocalDateTime.of(2026, 9, 18, 12, 34, 56)),
                Arguments.of("12:34:56-03:00", OffsetTime.class, OffsetTime.parse("12:34:56-03:00")),
                Arguments.of("2026-09-18T12:34:56+05:30", OffsetDateTime.class,
                        OffsetDateTime.parse("2026-09-18T12:34:56+05:30")),
                Arguments.of("2026-09-18T12:34:56+01:00[Europe/Paris]", ZonedDateTime.class,
                        ZonedDateTime.parse("2026-09-18T12:34:56+01:00[Europe/Paris]")),
                Arguments.of("PT25H0.5S", Duration.class, Duration.ofHours(25).plusMillis(500)),
                Arguments.of("P1Y2M3D", Period.class, Period.of(1, 2, 3)),
                Arguments.of("123E4567-E89B-12D3-A456-426614174000", UUID.class,
                        UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
                Arguments.of("123", int.class, 123),
                Arguments.of("9223372036854775807", long.class, Long.MAX_VALUE),
                Arguments.of("1.5", double.class, 1.5),
                Arguments.of("TRUE", boolean.class, true));
    }

    @ParameterizedTest
    @MethodSource("values")
    void additionalTargetsReadOnlyTheRequestedByteSpan(final String text, final Class<?> type,
                                                       final Object expected) {
        final byte[] bytes = ("77" + text + "77").getBytes(StandardCharsets.UTF_8);
        assertEquals(expected, TypeConverter.convert(bytes, 2, bytes.length - 2, type));
    }

    @ParameterizedTest
    @MethodSource("values")
    void emptySpansStayNullEvenForPrimitiveTokens(final String text, final Class<?> type,
                                                 final Object expected) {
        assertNull(TypeConverter.convert(new byte[0], 0, 0, type));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("128", Byte.class, NumberFormatException.class),
                Arguments.of("-129", byte.class, NumberFormatException.class),
                Arguments.of("256", Byte.class, NumberFormatException.class),
                Arguments.of("32768", Short.class, NumberFormatException.class),
                Arguments.of("-32769", short.class, NumberFormatException.class),
                Arguments.of("65536", Short.class, NumberFormatException.class),
                Arguments.of("9223372036854775808", Short.class, NumberFormatException.class),
                Arguments.of("1.5", Byte.class, NumberFormatException.class),
                Arguments.of("1.0", BigInteger.class, NumberFormatException.class),
                Arguments.of("1e9", BigInteger.class, NumberFormatException.class),
                Arguments.of("oops", Float.class, NumberFormatException.class),
                Arguments.of("ab", Character.class, IllegalArgumentException.class),
                Arguments.of("😀", char.class, IllegalArgumentException.class),
                Arguments.of("2023-02-29", LocalDate.class, DateTimeParseException.class),
                Arguments.of("18/09/2026", LocalDate.class, DateTimeParseException.class),
                Arguments.of("24:00:00", LocalTime.class, DateTimeParseException.class),
                Arguments.of("2026-09-18T12:00:00Z", LocalDateTime.class, DateTimeParseException.class),
                Arguments.of("12:00:00", OffsetTime.class, DateTimeParseException.class),
                Arguments.of("2026-09-18T12:00:00", OffsetDateTime.class, DateTimeParseException.class),
                Arguments.of("not-a-zone", ZonedDateTime.class, DateTimeParseException.class),
                Arguments.of("P1M", Duration.class, DateTimeParseException.class),
                Arguments.of("PT1H", Period.class, DateTimeParseException.class),
                Arguments.of("not-a-uuid", UUID.class, IllegalArgumentException.class));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void malformedAndOutOfRangeValuesRetainConversionExceptions(
            final String text, final Class<?> type, final Class<? extends Throwable> failure) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertThrows(failure, () -> TypeConverter.convert(bytes, 0, bytes.length, type));
    }

    @Test
    void nullIsNotAValidTargetForANonEmptyValue() {
        assertThrows(NullPointerException.class,
                () -> TypeConverter.convert(new byte[] {'1'}, 0, 1, null));
    }
}
