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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link TypeConverter}, the conversion backbone of the cursor API. */
class TypeConverterTest {

    enum Status { ACTIVE, INACTIVE }

    @Test
    void nullAndEmptyTextConvertToNullForEveryType() {
        assertNull(TypeConverter.convert(null, String.class));
        assertNull(TypeConverter.convert("", String.class));
        assertNull(TypeConverter.convert(null, Integer.class));
        assertNull(TypeConverter.convert("", BigDecimal.class));
    }

    @Test
    void stringPassesThroughWithoutCopying() {
        String value = "as-is";

        assertSame(value, TypeConverter.convert(value, String.class));
    }

    @Test
    void convertsNumericTypes() {
        assertEquals(42, TypeConverter.convert("42", Integer.class));
        assertEquals(-7, TypeConverter.convert("-7", Integer.class));
        assertEquals(9_999_999_999L, TypeConverter.convert("9999999999", Long.class));
        assertEquals(1.5, TypeConverter.convert("1.5", Double.class));
        assertEquals(new BigDecimal("10.50"), TypeConverter.convert("10.50", BigDecimal.class));
    }

    @Test
    void bigDecimalPreservesScale() {
        assertEquals(2, TypeConverter.convert("10.50", BigDecimal.class).scale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1"})
    void convertsTruthyBooleans(String text) {
        assertEquals(Boolean.TRUE, TypeConverter.convert(text, Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False", "0"})
    void convertsFalsyBooleans(String text) {
        assertEquals(Boolean.FALSE, TypeConverter.convert(text, Boolean.class));
    }

    @ParameterizedTest
    @CsvSource({"yes", "no", "2", "T"})
    void rejectsInvalidBooleanText(String text) {
        XmlException e = assertThrows(XmlException.class,
                () -> TypeConverter.convert(text, Boolean.class));

        assertTrue(e.getMessage().contains(text));
    }

    @Test
    void convertsIso8601Instants() {
        assertEquals(Instant.parse("2026-01-15T10:30:00Z"),
                TypeConverter.convert("2026-01-15T10:30:00Z", Instant.class));
    }

    @Test
    void convertsEnumsByConstantName() {
        assertEquals(Status.ACTIVE, TypeConverter.convert("ACTIVE", Status.class));
        assertEquals(Status.INACTIVE, TypeConverter.convert("INACTIVE", Status.class));
    }

    @Test
    @DisplayName("unsupported target types fail fast with XmlException")
    void rejectsUnsupportedTypes() {
        XmlException e = assertThrows(XmlException.class,
                () -> TypeConverter.convert("2026-01-15", java.time.LocalDate.class));

        assertTrue(e.getMessage().contains("java.time.LocalDate"));
    }
}
