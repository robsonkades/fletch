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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TypeConverter}, the byte-span conversion backbone of both
 * extraction styles. Values are framed by garbage guard bytes so every
 * assertion also proves the parsers honor the {@code [s, e)} bounds.
 */
class TypeConverterTest {

    enum Status { ACTIVE, INACTIVE }

    /** Converts {@code text} through a span surrounded by digit guard bytes. */
    private static <T> T convert(final String text, final Class<T> type) {
        final byte[] b = ("77" + text + "77").getBytes(StandardCharsets.UTF_8);
        return TypeConverter.convert(b, 2, b.length - 2, type);
    }

    @Test
    void emptySpanConvertsToNullForEveryType() {
        final byte[] b = "77".getBytes(StandardCharsets.UTF_8);
        assertNull(TypeConverter.convert(b, 1, 1, String.class));
        assertNull(TypeConverter.convert(b, 1, 1, Integer.class));
        assertNull(TypeConverter.convert(b, 2, 2, BigDecimal.class));
    }

    @Test
    void stringMaterializesExactlyTheSpan() {
        assertEquals("as-is", convert("as-is", String.class));
    }

    @Test
    void convertsNumericTypes() {
        assertEquals(42, convert("42", Integer.class));
        assertEquals(-7, convert("-7", Integer.class));
        assertEquals(9_999_999_999L, convert("9999999999", Long.class));
        assertEquals(1.5, convert("1.5", Double.class));
        assertEquals(new BigDecimal("10.50"), convert("10.50", BigDecimal.class));
    }

    @Test
    void intBoundaryValuesParseAndOverflowIsRejected() {
        assertEquals(Integer.MAX_VALUE, convert("2147483647", Integer.class));
        assertEquals(Integer.MIN_VALUE, convert("-2147483648", Integer.class));
        assertThrows(NumberFormatException.class, () -> convert("2147483648", Integer.class));
    }

    @Test
    void malformedNumericTextThrowsNumberFormatException() {
        assertThrows(NumberFormatException.class, () -> convert("abc", Integer.class));
        assertThrows(NumberFormatException.class, () -> convert("1.5.0", Double.class));
        assertThrows(NumberFormatException.class, () -> convert("--1", BigDecimal.class));
    }

    @Test
    void longMagnitudesBeyondEighteenDigitsStillParseExactly() {
        assertEquals(Long.MAX_VALUE, convert("9223372036854775807", Long.class));
        assertEquals(Long.MIN_VALUE, convert("-9223372036854775808", Long.class));
        assertThrows(NumberFormatException.class, () -> convert("9223372036854775808", Long.class));
    }

    @Test
    void bigDecimalPreservesScale() {
        assertEquals(2, convert("10.50", BigDecimal.class).scale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1"})
    void convertsTruthyBooleans(String text) {
        assertEquals(Boolean.TRUE, convert(text, Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False", "0"})
    void convertsFalsyBooleans(String text) {
        assertEquals(Boolean.FALSE, convert(text, Boolean.class));
    }

    @ParameterizedTest
    @CsvSource({"yes", "no", "2", "T"})
    void rejectsInvalidBooleanText(String text) {
        XmlException e = assertThrows(XmlException.class,
                () -> convert(text, Boolean.class));

        assertTrue(e.getMessage().contains(text));
    }

    @Test
    void convertsIso8601Instants() {
        assertEquals(Instant.parse("2026-01-15T10:30:00Z"),
                convert("2026-01-15T10:30:00Z", Instant.class));
    }

    @Test
    void instantVariantsMatchTheJdkParser() {
        for (final String text : new String[] {
                "2026-01-15T10:30:00.123456789Z",
                "2026-01-15T10:30:00+05:30",
                "2026-01-15T10:30:00.5-03:00",
                "2024-02-29T23:59:59Z",
        }) {
            assertEquals(Instant.parse(text), convert(text, Instant.class), text);
        }
    }

    @Test
    void convertsEnumsByConstantName() {
        assertEquals(Status.ACTIVE, convert("ACTIVE", Status.class));
        assertEquals(Status.INACTIVE, convert("INACTIVE", Status.class));
    }

    @Test
    @DisplayName("unsupported target types fail fast with XmlException")
    void rejectsUnsupportedTypes() {
        XmlException e = assertThrows(XmlException.class,
                () -> convert("2026-01-15", java.time.LocalDate.class));

        assertTrue(e.getMessage().contains("java.time.LocalDate"));
    }
}
