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
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalDateConversionTest {
    @Test
    void everyDateInAGregorianCycleMatchesTheJdkWithinAnOffsetSpan() {
        final LocalDate end = LocalDate.of(2400, 1, 1);
        for (LocalDate date = LocalDate.of(2000, 1, 1); date.isBefore(end); date = date.plusDays(1)) {
            assertEquals(date, convert(date.toString()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0000-01-01", "0000-02-29", "0001-01-01", "9999-12-31",
            "1900-02-28", "2000-02-29", "2100-03-01", "2400-02-29",
            "-0001-01-01", "-0400-02-29", "+10000-01-01", "+12026-09-18",
            "-999999999-01-01", "+999999999-12-31"
    })
    void boundaryYearsAndExtendedFormsMatchTheJdk(final String text) {
        assertEquals(LocalDate.parse(text), convert(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2023-02-29", "1900-02-29", "2100-02-29", "2024-04-31",
            "2024-00-01", "2024-13-01", "2024-01-00", "2024-01-32",
            "2024/02/29", "2024-2-29", "2024-02-9", "24-02-29",
            "2024-02-29Z", "2024-02-29 ", " 2024-02-29", "+2024-02-29",
            "-0000-01-01", "10000-01-01", "+1000000000-01-01",
            "20x4-02-29", "2024-0x-29", "2024-02-2x", "٢٠٢٤-02-29", "😀24-02-29"
    })
    void invalidInputRetainsTheJdkExceptionDetails(final String text) {
        final DateTimeParseException expected = assertThrows(DateTimeParseException.class, () -> LocalDate.parse(text));
        final DateTimeParseException actual = assertThrows(DateTimeParseException.class, () -> convert(text));
        assertEquals(expected.getParsedString(), actual.getParsedString());
        assertEquals(expected.getErrorIndex(), actual.getErrorIndex());
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    @Test
    void decodedDatesAndExtendedChoicesWorkAcrossInputForms() {
        final String xml = "<r d=\"2024-02-&#50;9\"><v> <![CDATA[2024-]]>02-29 </v>"
                + "<alt>+12026-09-18</alt></r>";
        final XmlExtractor<List<LocalDate>> extractor = root -> root.child("r", c -> List.of(
                c.value("v", LocalDate.class), c.firstOf(LocalDate.class, "missing", "alt"),
                c.attribute("d", LocalDate.class)));
        final XmlMapping<List<LocalDate>> mapping = Xml.mapping(() -> new LocalDate[3])
                .text("/r/v", (d, v) -> d[0] = v.as(LocalDate.class))
                .firstOf((d, v) -> d[1] = v.as(LocalDate.class), "/r/missing", "/r/alt")
                .attr("/r@d", (d, v) -> d[2] = v.as(LocalDate.class))
                .build(Arrays::asList);
        final List<LocalDate> expected = List.of(LocalDate.of(2024, 2, 29),
                LocalDate.of(12026, 9, 18), LocalDate.of(2024, 2, 29));
        for (final ValueConversionTest.Input input : ValueConversionTest.Input.values()) {
            assertEquals(expected, input.read(xml, extractor, mapping), input.name());
        }
    }

    private static LocalDate convert(final String text) {
        final byte[] bytes = ("xx" + text + "yy").getBytes(StandardCharsets.UTF_8);
        return TypeConverter.convert(bytes, 2, bytes.length - 2, LocalDate.class);
    }
}
