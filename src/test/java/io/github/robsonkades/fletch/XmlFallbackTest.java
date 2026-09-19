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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class XmlFallbackTest {
    enum Source {
        ELEMENT {
            @Override <T> T read(final XmlCursor r, final Function<? super String, ? extends T> converter,
                                 final Supplier<? extends T> fallback) {
                return r.valueWith("v", converter, fallback);
            }
        },
        ATTRIBUTE {
            @Override <T> T read(final XmlCursor r, final Function<? super String, ? extends T> converter,
                                 final Supplier<? extends T> fallback) {
                return r.attributeWith("v", converter, fallback);
            }
        };

        abstract <T> T read(XmlCursor r, Function<? super String, ? extends T> converter,
                            Supplier<? extends T> fallback);
    }

    static Stream<Arguments> branches() {
        return Stream.of(
                Arguments.of(Source.ELEMENT, "<r><v>  hello &amp; <![CDATA[world]]> </v></r>", "HELLO & WORLD", true),
                Arguments.of(Source.ELEMENT, "<r/>", "fallback", false),
                Arguments.of(Source.ELEMENT, "<r><v/></r>", "fallback", false),
                Arguments.of(Source.ELEMENT, "<r><v></v></r>", "fallback", false),
                Arguments.of(Source.ELEMENT, "<r><v> \r\n\t </v></r>", "fallback", false),
                Arguments.of(Source.ATTRIBUTE, "<r v='hello &amp; world'/>", "HELLO & WORLD", true),
                Arguments.of(Source.ATTRIBUTE, "<r/>", "fallback", false),
                Arguments.of(Source.ATTRIBUTE, "<r v=''/>", "fallback", false),
                Arguments.of(Source.ATTRIBUTE, "<r v=' \t &#10; '/>", "   \n ", true));
    }

    @ParameterizedTest
    @MethodSource("branches")
    void onlyTheSelectedFunctionRunsOnce(final Source source, final String xml, final String expected,
                                         final boolean present) {
        for (final XmlPresenceTest.Input input : XmlPresenceTest.Input.values()) {
            final var conversions = new AtomicInteger();
            final var fallbacks = new AtomicInteger();
            final String value = input.read(xml, doc -> doc.child("r", r -> source.read(r, text -> {
                conversions.incrementAndGet();
                return text.toUpperCase(java.util.Locale.ROOT);
            }, () -> {
                fallbacks.incrementAndGet();
                return "fallback";
            })));
            assertEquals(expected, value, input.name());
            assertEquals(present ? 1 : 0, conversions.get(), input.name());
            assertEquals(present ? 0 : 1, fallbacks.get(), input.name());
        }
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void converterNullResultsDoNotTriggerFallback(final Source source) {
        final Object result = Xml.extract("<r v='present'><v>present</v></r>", doc -> doc.child("r", r ->
                source.read(r, text -> null, () -> { throw new AssertionError("Unexpected fallback"); })));
        assertNull(result);
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void conversionExceptionsPropagateWithoutInvokingFallback(final Source source) {
        final var failure = new IllegalArgumentException("Invalid value");
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> Xml.extract("<r v='bad'><v>bad</v></r>", doc -> doc.child("r", r ->
                        source.read(r, text -> { throw failure; },
                                () -> { throw new AssertionError("Unexpected fallback"); })))));
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void fallbackMayReturnNullAndItsExceptionsPropagate(final Source source) {
        assertNull(Xml.extract("<r/>", doc -> doc.child("r", r ->
                source.read(r, text -> { throw new AssertionError(text); }, () -> null))));
        final var failure = new IllegalStateException("Missing required default");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> Xml.extract("<r/>", doc -> doc.child("r", r ->
                        source.read(r, Function.identity(), () -> { throw failure; })))));
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void bothCallbacksMustBeValidBeforeReading(final Source source) {
        Xml.extract("<r v='ok'><v>ok</v></r>", doc -> doc.child("r", r -> {
            assertThrows(NullPointerException.class, () -> source.read(r, null, () -> "fallback"));
            assertThrows(NullPointerException.class, () -> source.read(r, Function.identity(), null));
            assertEquals("ok", source.read(r, Function.identity(), () -> "fallback"));
            return null;
        }));
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void invalidXmlAndLimitsAreNeverReplacedByFallback(final Source source) {
        assertThrows(XmlException.class,
                () -> Xml.extract("<r v='&bad;'><v>&bad;</v></r>", doc -> doc.child("r", r ->
                        source.read(r, Function.identity(), () -> { throw new AssertionError("fallback"); }))));
        assertThrows(XmlException.class,
                () -> Xml.extract("<r v='long'><v>long</v></r>", XmlLimits.builder().maxTextBytes(2).build(),
                        doc -> doc.child("r", r ->
                                source.read(r, Function.identity(), () -> { throw new AssertionError("fallback"); }))));
    }

    @Test
    void fallbackConsumesTheFirstEmptyOccurrenceAndPreservesLaterOccurrences() {
        final List<String> values = Xml.extract("<r><v/><v>second</v></r>", doc -> doc.child("r", r -> {
            assertTrue(r.exists("v"));
            final String first = r.valueWith("v", Function.identity(), () -> "default");
            assertTrue(r.exists("v"));
            final String second = r.valueWith("v", Function.identity(), () -> "default");
            assertFalse(r.exists("v"));
            return List.of(first, second);
        }));
        assertEquals(List.of("default", "second"), values);
    }

    @Test
    void functionsAndFallbacksCanReturnDifferentSubtypes() {
        final Function<CharSequence, Integer> converter = text -> Integer.valueOf(text.toString());
        final Supplier<Double> fallback = () -> 2.5;
        final Number present = Xml.extract("<r><v>7</v></r>",
                doc -> doc.child("r", r -> r.<Number>valueWith("v", converter, fallback)));
        final Number absent = Xml.extract("<r/>",
                doc -> doc.child("r", r -> r.<Number>valueWith("v", converter, fallback)));
        assertEquals(7, present);
        assertEquals(2.5, absent);
    }

    @Test
    void fallbackMayReadAnotherBufferedFieldOfTheSameCursor() {
        final String value = Xml.extract("<r><alternative>found</alternative></r>", doc -> doc.child("r", r ->
                r.valueWith("preferred", Function.identity(),
                        () -> r.valueWith("alternative", Function.identity(), () -> "default"))));
        assertEquals("found", value);
    }

    @Test
    void fallbackMayReenterExtraction() {
        final String value = Xml.extract("<r/>", doc -> doc.child("r", r ->
                r.valueWith("missing", Function.identity(),
                        () -> Xml.extract("<fallback><v>nested</v></fallback>",
                                nested -> nested.child("fallback", c -> c.value("v", String.class))))));
        assertEquals("nested", value);
    }
}
