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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ValueConversionTest {
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Function<String, LocalDate> READ_DATE = text -> LocalDate.parse(text, BR_DATE);

    enum Input {
        CURSOR_STRING, CURSOR_BYTES, CURSOR_STREAM,
        MAPPING_STRING, MAPPING_BYTES, MAPPING_STREAM, SESSION;

        <T> T read(final String xml, final XmlExtractor<T> extractor, final XmlMapping<T> mapping) {
            final byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            return switch (this) {
                case CURSOR_STRING -> Xml.extract(xml, extractor);
                case CURSOR_BYTES -> Xml.extract(bytes, extractor);
                case CURSOR_STREAM -> Xml.extract(new ByteArrayInputStream(bytes), extractor);
                case MAPPING_STRING -> Xml.extract(xml, mapping);
                case MAPPING_BYTES -> Xml.extract(bytes, mapping);
                case MAPPING_STREAM -> Xml.extract(new ByteArrayInputStream(bytes), mapping);
                case SESSION -> {
                    try (var session = mapping.openSession()) {
                        yield session.extract(new ByteArrayInputStream(bytes));
                    }
                }
            };
        }
    }

    static Stream<Arguments> builtInValues() {
        return Stream.concat(AdditionalTypeConverterTest.values(), Stream.of(
                Arguments.of("2026-09-18T12:00:00Z", Instant.class, Instant.parse("2026-09-18T12:00:00Z")),
                Arguments.of("12.50", BigDecimal.class, new BigDecimal("12.50")),
                Arguments.of("ACTIVE", Status.class, Status.ACTIVE),
                Arguments.of("ação", String.class, "ação"),
                Arguments.of("123", Integer.class, 123),
                Arguments.of("456", Long.class, 456L),
                Arguments.of("1.5", Double.class, 1.5),
                Arguments.of("true", Boolean.class, true)));
    }

    enum Status { ACTIVE }

    @ParameterizedTest
    @MethodSource("builtInValues")
    void cursorAndMappingConvertTextAttributesAndChoicesConsistently(
            final String text, final Class<?> type, final Object expected) {
        final String xml = "<r a=\"" + text + "\"><second>" + text + "</second>"
                + "<v> <![CDATA[" + text + "]]> </v></r>";
        final XmlExtractor<List<Object>> extractor = doc -> doc.child("r", r -> {
            final Object value = r.value("v", type); // Read past second; firstOf must replay it.
            final Object alternative = r.firstOf(type, "first", "second");
            return List.of(value, alternative, r.attribute("a", type));
        });
        final XmlMapping<List<Object>> mapping = Xml.mapping(() -> new Object[3])
                .text("/r/v", (d, v) -> d[0] = v.as(type))
                .firstOf((d, v) -> d[1] = v.as(type), "/r/first", "/r/second")
                .attr("/r@a", (d, v) -> d[2] = v.as(type))
                .build(Arrays::asList);
        for (final Input input : Input.values()) {
            assertEquals(List.of(expected, expected, expected), input.read(xml, extractor, mapping), input.name());
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.robsonkades.fletch.AdditionalTypeConverterTest#invalidValues")
    void invalidBuiltInValuesKeepTheirExceptionTypesThroughBothApis(
            final String text, final Class<?> type, final Class<? extends Throwable> failure) {
        final XmlMapping<Object> mapping = Xml.mapping(() -> new Object[1])
                .text("/r/v", (d, v) -> d[0] = v.as(type)).build(d -> d[0]);
        for (final Input input : Input.values()) {
            assertThrows(failure, () -> input.read("<r><v>" + text + "</v></r>",
                    doc -> doc.child("r", r -> r.value("v", type)), mapping), input.name());
        }
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void oneCustomFunctionWorksForAttributesTextAndChoices(final Input input) {
        final String xml = "<r a=\"18/09/2026\"><second>20/09/2026</second>"
                + "<v> 19/09/2026 </v></r>";
        final XmlExtractor<List<LocalDate>> extractor = doc -> doc.child("r", r -> {
            final LocalDate value = r.valueWith("v", READ_DATE);
            final LocalDate alternative = r.firstOfWith(READ_DATE, "first", "second");
            return List.of(r.attributeWith("a", READ_DATE), value, alternative);
        });
        final XmlMapping<List<LocalDate>> mapping = Xml.mapping(() -> new LocalDate[3])
                .attr("/r@a", (d, v) -> d[0] = v.convert(READ_DATE))
                .text("/r/v", (d, v) -> d[1] = v.convert(READ_DATE))
                .firstOf((d, v) -> d[2] = v.convert(READ_DATE), "/r/first", "/r/second")
                .build(Arrays::asList);
        assertEquals(List.of(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 20)),
                input.read(xml, extractor, mapping));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void functionsReceiveDecodedTextWithExistingWhitespaceRules(final Input input) {
        final String xml = "<r a=\" Café\t&amp;&#10;😀 \"><v> \r\n A&amp;<![CDATA[<B>]]>😀 \t </v></r>";
        final XmlExtractor<List<String>> extractor = doc -> doc.child("r", r ->
                List.of(r.valueWith("v", Function.identity()), r.attributeWith("a", Function.identity())));
        final XmlMapping<List<String>> mapping = Xml.mapping(() -> new String[2])
                .text("/r/v", (d, v) -> d[0] = v.convert(Function.identity()))
                .attr("/r@a", (d, v) -> d[1] = v.convert(Function.identity()))
                .build(Arrays::asList);
        assertEquals(List.of("A&<B>😀", " Café &\n😀 "), input.read(xml, extractor, mapping));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void absentEmptyAndBlankTextNeverInvokeTheConverter(final Input input) {
        final Function<String, String> forbidden = text -> { throw new AssertionError("Unexpected conversion: " + text); };
        final String xml = "<r a=\"\"><empty/><blank> \n\t </blank></r>";
        final XmlExtractor<List<String>> extractor = doc -> doc.child("r", r -> Arrays.asList(
                r.valueWith("empty", forbidden), r.valueWith("blank", forbidden),
                r.valueWith("missing", forbidden), r.attributeWith("a", forbidden),
                r.attributeWith("missing", forbidden), r.firstOfWith(forbidden, "absent", "absent2")));
        final XmlMapping<List<String>> mapping = Xml.mapping(() -> new String[6])
                .text("/r/empty", (d, v) -> d[0] = v.convert(forbidden))
                .text("/r/blank", (d, v) -> d[1] = v.convert(forbidden))
                .text("/r/missing", (d, v) -> d[2] = v.convert(forbidden))
                .attr("/r@a", (d, v) -> d[3] = v.convert(forbidden))
                .attr("/r@missing", (d, v) -> d[4] = v.convert(forbidden))
                .firstOf((d, v) -> d[5] = v.convert(forbidden), "/r/absent", "/r/absent2")
                .build(Arrays::asList);
        assertEquals(Arrays.asList(null, null, null, null, null, null), input.read(xml, extractor, mapping));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void whitespaceOnlyAttributesRemainValuesForCustomConversion(final Input input) {
        final String result = input.read("<r a=\" \t \"/>",
                doc -> doc.child("r", r -> r.attributeWith("a", text -> "[" + text + "]")),
                Xml.mapping(() -> new String[1]).attr("/r@a", (d, v) ->
                        d[0] = v.convert(text -> "[" + text + "]")).build(d -> d[0]));
        assertEquals("[   ]", result);
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void applicationExceptionsPropagateUnwrappedAndSubsequentExtractionsWork(final Input input) {
        final var failure = new IllegalStateException("custom conversion rejected value");
        final Function<String, String> converter = text -> {
            if ("bad".equals(text)) throw failure;
            return text;
        };
        final XmlExtractor<String> extractor = doc -> doc.child("r", r -> r.valueWith("v", converter));
        final XmlMapping<String> mapping = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(converter)).build(d -> d[0]);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> input.read("<r><v>bad</v></r>", extractor, mapping)));
        assertEquals("good", input.read("<r><v>good</v></r>", extractor, mapping));
    }

    @Test
    void theSameSessionRecoversAfterCustomConversionFailure() {
        final var mapping = Xml.mapping(() -> new LocalDate[1])
                .text("/r/date", (d, v) -> d[0] = v.convert(READ_DATE)).build(d -> d[0]);
        try (var session = mapping.openSession()) {
            assertThrows(java.time.format.DateTimeParseException.class,
                    () -> session.extract("<r><date>31/02/2026</date></r>"));
            assertEquals(LocalDate.of(2026, 9, 18), session.extract("<r><date>18/09/2026</date></r>"));
        }
    }

    @Test
    void nullFunctionsFailBeforeCursorNavigation() {
        Xml.extract("<r a=\"1\"><v>2</v><alternative>3</alternative></r>", doc -> doc.child("r", r -> {
            assertThrows(NullPointerException.class, () -> r.valueWith("v", null));
            assertThrows(NullPointerException.class, () -> r.firstOfWith(null, "alternative", "other"));
            assertThrows(NullPointerException.class, () -> r.attributeWith("a", null));
            final Integer value = r.valueWith("v", Integer::valueOf);
            final Integer alternative = r.firstOfWith(Integer::valueOf, "alternative", "other");
            final Integer attribute = r.attributeWith("a", Integer::valueOf);
            assertEquals(2, value);
            assertEquals(3, alternative);
            assertEquals(1, attribute);
            return null;
        }));
    }

    @Test
    void nullMappingFunctionsAndTypesFailAtTheCallSite() {
        final var mapping = Xml.mapping(() -> new String[1]).text("/r/v", (d, v) -> {
            assertThrows(NullPointerException.class, () -> v.convert(null));
            assertThrows(NullPointerException.class, () -> v.as(null));
            d[0] = v.convert(Function.identity());
        }).build(d -> d[0]);
        assertEquals("ok", Xml.extract("<r><v>ok</v></r>", mapping));
    }

    @Test
    void nullConverterResultsConsumeOnlyTheSelectedOccurrence() {
        final var calls = new AtomicInteger();
        final Function<String, String> converter = text -> { calls.incrementAndGet(); return null; };
        Xml.extract("<r><second>one</second><first>two</first><v>three</v><v>four</v></r>",
                doc -> doc.child("r", r -> {
                    assertNull(r.firstOfWith(converter, "first", "second"));
                    assertEquals("two", r.valueWith("first", Function.identity()));
                    assertNull(r.valueWith("v", converter));
                    assertEquals("four", r.valueWith("v", Function.identity()));
                    return null;
                }));
        assertEquals(2, calls.get());
    }

    @Test
    void emptyFirstAlternativeDoesNotInvokeAConverterOrFallThrough() {
        Xml.extract("<r><second/><first>two</first></r>", doc -> doc.child("r", r -> {
            assertNull(r.firstOfWith(text -> { throw new AssertionError(text); }, "first", "second"));
            assertEquals("two", r.valueWith("first", Function.identity()));
            return null;
        }));
    }

    @Test
    void genericFunctionsCanBuildDomainTypesAndReturnNullFromMappings() {
        record Code(String value) {}
        final Function<CharSequence, Code> converter = text -> new Code(text.toString());
        final Object result = Xml.extract("<r><v>ABC</v></r>",
                doc -> doc.child("r", r -> r.valueWith("v", converter)));
        assertEquals(new Code("ABC"), result);
        final XmlMapping<Code> mapping = Xml.mapping(() -> new Code[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(converter)).build(d -> d[0]);
        assertEquals(result, Xml.extract("<r><v>ABC</v></r>", mapping));
        final XmlMapping<String> nullable = Xml.mapping(() -> new String[] {"initial"})
                .text("/r/v", (d, v) -> d[0] = v.convert(text -> null)).build(d -> d[0]);
        assertNull(Xml.extract("<r><v>ABC</v></r>", nullable));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISO-8859-1", "UTF-16"})
    void conversionsRunAfterLegacyEncodingTranscoding(final String encoding) {
        final String xml = "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?><r><v>ação</v></r>";
        final byte[] bytes = xml.getBytes(java.nio.charset.Charset.forName(encoding));
        final var mapping = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(text -> "[" + text + "]")).build(d -> d[0]);
        assertEquals("[ação]", Xml.extract(bytes,
                doc -> doc.child("r", r -> r.valueWith("v", text -> "[" + text + "]"))));
        assertEquals("[ação]", Xml.extract(new ByteArrayInputStream(bytes), mapping));
    }

    @Test
    void xmlValidationAndLimitsRunBeforeCustomFunctions() {
        final Function<String, String> forbidden = text -> { throw new AssertionError(text); };
        final var mapping = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(forbidden)).build(d -> d[0]);
        final XmlExtractor<String> extractor = doc -> doc.child("r", r -> r.valueWith("v", forbidden));
        final byte[] malformed = {'<', 'r', '>', '<', 'v', '>', (byte) 0xc0, (byte) 0x80,
                '<', '/', 'v', '>', '<', '/', 'r', '>'};
        assertThrows(XmlException.class, () -> Xml.extract(malformed, mapping));
        assertThrows(XmlException.class, () -> Xml.extract(malformed, extractor));
        final var limits = XmlLimits.builder().maxTextBytes(2).build();
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>long</v></r>", limits, mapping));
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>long</v></r>", limits, extractor));
    }

    @Test
    void customFunctionsCanReenterExtractionWithoutCorruptingOuterValues() {
        final Function<String, String> nested = text -> text + Xml.extract("<n><v>inner</v></n>",
                doc -> doc.child("n", n -> n.valueWith("v", String::toUpperCase)));
        final var mapping = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(nested)).build(d -> d[0]);
        assertEquals("outerINNER", Xml.extract("<r><v>outer</v></r>", mapping));
        assertEquals("outerINNER", Xml.extract("<r><v>outer</v></r>",
                doc -> doc.child("r", r -> r.valueWith("v", nested))));
    }

    @Test
    void sharedStatelessConvertersWorkAcrossConcurrentMappingExtractions() throws Exception {
        final var mapping = Xml.mapping(() -> new LocalDate[1])
                .text("/r/v", (d, v) -> d[0] = v.convert(READ_DATE)).build(d -> d[0]);
        final var pool = Executors.newFixedThreadPool(4);
        try {
            final List<Future<LocalDate>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int day = i % 28 + 1;
                final String xml = "<r><v>" + String.format(Locale.ROOT, "%02d/09/2026", day) + "</v></r>";
                results.add(pool.submit(() -> Xml.extract(xml, mapping)));
            }
            for (int i = 0; i < results.size(); i++) {
                assertEquals(LocalDate.of(2026, 9, i % 28 + 1), results.get(i).get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void mappingByteAccessorsRemainAvailableWithoutDecodingText() {
        final XmlValue value = new XmlValue() {
            @Override public String asString() { throw new AssertionError("Unexpected String allocation"); }
            @Override public String asCanonical() { return asString(); }
            @Override public long asLong() { return 17L; }
            @Override public int asInt() { return 17; }
            @Override public double asDouble() { return 17.0; }
            @Override public BigDecimal asDecimal() { return BigDecimal.valueOf(17); }
            @Override public boolean asBoolean() { return true; }
            @Override public Instant asInstant() { return Instant.EPOCH; }
            @Override public <E extends Enum<E>> E asEnum(final Class<E> type) { return Enum.valueOf(type, "ACTIVE"); }
        };
        assertEquals(17, value.as(Integer.class));
        assertEquals(17, value.as(int.class));
        assertEquals(17L, value.as(Long.class));
        assertEquals(17.0, value.as(double.class));
        assertEquals((byte) 17, value.as(Byte.class));
        assertEquals((short) 17, value.as(short.class));
        assertEquals(BigDecimal.valueOf(17), value.as(BigDecimal.class));
        assertEquals(true, value.as(boolean.class));
        assertEquals(Instant.EPOCH, value.as(Instant.class));
        assertEquals(Status.ACTIVE, value.as(Status.class));
    }

    @Test
    void missingPrimitiveTargetsStayNullableInBothApis() {
        final var mapping = Xml.mapping(() -> new Integer[1])
                .text("/r/v", (d, v) -> d[0] = v.as(int.class)).build(d -> d[0]);
        for (final Input input : Input.values()) {
            assertNull(input.read("<r><v> </v></r>",
                    doc -> doc.child("r", r -> r.value("v", int.class)), mapping), input.name());
        }
    }

    @Test
    void customConversionsPreserveChoiceAndGroupBindingRules() {
        final var calls = new AtomicInteger();
        final Function<String, String> converter = text -> {
            calls.incrementAndGet();
            return text.equals("null") ? null : text.toUpperCase(Locale.ROOT);
        };
        final XmlMapping<List<String>> mapping = Xml.mapping(() -> new ArrayList<String>())
                .group("/r/item", () -> new String[1], (d, item) -> d.add(item[0]))
                    .firstOf((d, v) -> d[0] = v.convert(converter), "a", "b")
                    .endGroup()
                .build(d -> d);
        final String xml = "<r><item><a/><b>ok</b></item>"
                + "<item><a>null</a><b>ignored</b></item><item><a>again</a></item></r>";
        assertEquals(Arrays.asList("OK", null, "AGAIN"), Xml.extract(xml, mapping));
        assertEquals(3, calls.get());
    }
}
