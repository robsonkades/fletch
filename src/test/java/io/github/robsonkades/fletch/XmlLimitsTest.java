/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class XmlLimitsTest {
    private static final String DOCUMENT = "<r><v>ok</v></r>";
    private static final XmlExtractor<String> VALUE = d -> d.child("r", r -> r.value("v", String.class));
    private static final XmlMapping<String> MAPPING = mapping(false);
    private static final XmlMapping<String> STRICT = mapping(true);

    private static XmlMapping<String> mapping(final boolean strict) {
        final var builder = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.asString());
        if (strict) builder.strictSkip();
        return builder.build(d -> d[0]);
    }

    enum Input {
        CURSOR_STRING, CURSOR_BYTES, CURSOR_STREAM,
        MAPPING_STRING, MAPPING_BYTES, MAPPING_STREAM, MAPPING_DRIP,
        STRICT_STRING, STRICT_BYTES, STRICT_STREAM, STRICT_DRIP;

        String extract(final String xml, final XmlLimits limits) {
            final byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            return switch (this) {
                case CURSOR_STRING -> Xml.extract(xml, limits, VALUE);
                case CURSOR_BYTES -> Xml.extract(bytes, limits, VALUE);
                case CURSOR_STREAM -> Xml.extract(new ByteArrayInputStream(bytes), limits, VALUE);
                case MAPPING_STRING -> Xml.extract(xml, limits, MAPPING);
                case MAPPING_BYTES -> Xml.extract(bytes, limits, MAPPING);
                case MAPPING_STREAM -> Xml.extract(new ByteArrayInputStream(bytes), limits, MAPPING);
                case STRICT_STRING -> Xml.extract(xml, limits, STRICT);
                case STRICT_BYTES -> Xml.extract(bytes, limits, STRICT);
                case STRICT_STREAM -> Xml.extract(new ByteArrayInputStream(bytes), limits, STRICT);
                case MAPPING_DRIP, STRICT_DRIP -> {
                    final var engine = new XmlMappingEngine<>(this == STRICT_DRIP ? STRICT : MAPPING, 16);
                    engine.limits = limits;
                    yield engine.extract(new CountingStream(bytes, 1));
                }
            };
        }
    }

    @ParameterizedTest @EnumSource(Input.class)
    void inputAtOrBelowTheByteLimitIsAccepted(final Input input) {
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.builder().maxInputBytes(16).build()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.builder().maxInputBytes(17).build()));
        assertThrows(XmlException.class,
                () -> input.extract(DOCUMENT, XmlLimits.builder().maxInputBytes(15).build()));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void textLimitsCountRawUtf8BeforeDecodingAndTrimming(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxTextBytes(6).build();
        assertEquals("&", input.extract("<r><v>&amp;</v></r>", limits)); // 5 bytes
        assertEquals("&", input.extract("<r><v> &amp;</v></r>", limits)); // 6 bytes
        assertThrows(XmlException.class, () -> input.extract("<r><v> &amp; </v></r>", limits));
        assertEquals("é😀", input.extract("<r><v>é😀</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><v>é😀x</v></r>", limits));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void textLimitAccumulatesAcrossCdataAndNestedElements(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxTextBytes(4).build();
        assertEquals("abc", input.extract("<r><v>a<![CDATA[b]]><x>c</x></v></r>", limits));
        assertEquals("abcd", input.extract("<r><v>a<![CDATA[bc]]><x>d</x></v></r>", limits));
        assertThrows(XmlException.class,
                () -> input.extract("<r><v>a<![CDATA[bc]]><x>de</x></v></r>", limits));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void ignoredAttributesObeyContentAndCountLimits(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxAttributesPerElement(2).maxTextBytes(3).build();
        assertEquals("ok", input.extract("<r a='xx'><v>ok</v></r>", limits));
        assertEquals("ok", input.extract("<r a='xxx' b=''><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r a='xxxx'><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><x a='' b='' c=''/><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><x><y a='xxxx'/></x><v>ok</v></r>", limits));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void zeroLimitsPermitOnlyEmptyContentAndNoAttributes(final Input input) {
        assertNull(input.extract("<r><v/></r>", XmlLimits.builder().maxTextBytes(0).build()));
        assertNull(input.extract("<r a=''><v></v></r>", XmlLimits.builder().maxTextBytes(0).build()));
        assertThrows(XmlException.class, () -> input.extract("<r><v> </v></r>", XmlLimits.builder().maxTextBytes(0).build()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.builder().maxAttributesPerElement(0).build()));
        assertThrows(XmlException.class, () -> input.extract("<r a=''><v>ok</v></r>",
                XmlLimits.builder().maxAttributesPerElement(0).build()));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void depthIncludesSkippedEmptyAndMixedContentElements(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxDepth(3).build();
        assertEquals("ok", input.extract(DOCUMENT, limits));
        assertEquals("ok", input.extract("<r><junk><x/></junk><v>ok</v></r>", limits));
        assertEquals("ok", input.extract("<r><v><x>ok</x></v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><junk><x><y/></x></junk><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><v><x><y/>ok</x></v></r>", limits));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void elementBudgetIncludesSkippedAndMixedContentStartTags(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxElements(4).build();
        assertEquals("ok", input.extract("<r><x/><v>ok</v></r>", limits));
        assertEquals("ok", input.extract("<r><x><y/></x><v>ok</v></r>", limits));
        assertEquals("ok", input.extract("<r><v><x/><y/>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><x><y/><z/></x><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><v><x/><y/><z/>ok</v></r>", limits));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void nameLimitsCountUtf8ForSkippedElementsAndAttributes(final Input input) {
        final XmlLimits limits = XmlLimits.builder().maxNameBytes(3).build();
        assertEquals("ok", input.extract("<r><é/><v>ok</v></r>", limits));
        assertEquals("ok", input.extract("<r><世 é=''/><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><éé/><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><x éé=''/><v>ok</v></r>", limits));
        assertThrows(XmlException.class, () -> input.extract("<r><x></long><v>ok</v></r>", limits));
    }

    @ParameterizedTest @ValueSource(ints = {470, 471, 472, 473, 500, 501, 502, 511, 65512})
    void resourceCountersAndUtf8ValuesSurviveActualWindowRefills(final int padding) {
        final String xml = "<r>" + " ".repeat(padding)
                + "<unknown a='&amp;'><a/></unknown><v><![CDATA[é]]>😀</v></r>";
        final XmlLimits exact = XmlLimits.builder().maxInputBytes(xml.getBytes(StandardCharsets.UTF_8).length)
                .maxDepth(3).maxElements(4).maxNameBytes(7).maxAttributesPerElement(1).maxTextBytes(6).build();
        for (Input input : List.of(Input.MAPPING_DRIP, Input.STRICT_DRIP)) {
            assertEquals("é😀", input.extract(xml, exact), input.name());
            assertThrows(XmlException.class,
                    () -> input.extract(xml, XmlLimits.builder().maxElements(3).build()), input.name());
            assertThrows(XmlException.class,
                    () -> input.extract(xml, XmlLimits.builder().maxDepth(2).build()), input.name());
        }
    }

    @Test
    void nameLimitsAreEnforcedWhileAStartTagSpansWindows() {
        final String name = "x".repeat(513);
        final String xml = "<r><" + name + "></" + name + "><v>ok</v></r>";
        assertEquals("ok", Input.STRICT_DRIP.extract(xml, XmlLimits.builder().maxNameBytes(513).build()));
        assertThrows(XmlException.class,
                () -> Input.STRICT_DRIP.extract(xml, XmlLimits.builder().maxNameBytes(512).build()));
    }

    @ParameterizedTest @EnumSource(Input.class)
    void limitsAndCountersAreResetAfterFailedAndSuccessfulExtractions(final Input input) {
        assertThrows(XmlException.class, () -> input.extract(DOCUMENT, XmlLimits.builder().maxElements(1).build()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.defaults()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.builder().maxElements(2).build()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.builder().maxElements(2).build()));
        assertEquals("ok", input.extract(DOCUMENT, XmlLimits.defaults()));
    }

    @Test
    void cursorReplayCountsEachDocumentElementOnlyOnce() {
        final String xml = "<r><a><x>one</x><y/></a><b>two</b><c>three</c></r>";
        final XmlExtractor<List<String>> outOfOrder = d -> d.child("r", r -> {
            final String b = r.value("b", String.class);
            final String a = r.child("a", n -> n.value("x", String.class));
            final String c = r.value("c", String.class);
            return List.of(a, b, c);
        });
        final XmlLimits six = XmlLimits.builder().maxElements(6).maxDepth(3).build();
        assertEquals(List.of("one", "two", "three"), Xml.extract(xml, six, outOfOrder));
        assertThrows(XmlException.class, () -> Xml.extract(xml,
                XmlLimits.builder().maxElements(5).build(), outOfOrder));
        final XmlExtractor<List<String>> replayValues = d -> d.child("r", r -> {
            final String c = r.value("c", String.class);
            return List.of(r.value("a", String.class), r.value("b", String.class), c);
        });
        assertEquals(List.of("one", "two", "three"), Xml.extract(xml, six, replayValues));
    }

    @Test
    void unreadChildrenAreLimitedWhileTheCursorDrainsTheirParent() {
        final XmlExtractor<Integer> rootOnly = d -> d.child("r", r -> 7);
        assertThrows(XmlException.class, () -> Xml.extract("<r><a><b/></a></r>",
                XmlLimits.builder().maxDepth(2).build(), rootOnly));
        assertThrows(XmlException.class, () -> Xml.extract("<r><a/><b/></r>",
                XmlLimits.builder().maxElements(2).build(), rootOnly));
    }

    @Test
    void limitsRejectElementsBeforeTheirDraftSupplierRuns() {
        final XmlMapping<Void> grouped = Xml.mapping(Object::new)
                .group("/r/item", () -> { fail("over-budget element must not allocate a group draft"); return new Object(); },
                        (r, item) -> {}).endGroup().build(d -> null);
        assertThrows(XmlException.class, () -> Xml.extract("<r><item/></r>",
                XmlLimits.builder().maxElements(1).build(), grouped));
        assertThrows(XmlException.class, () -> Xml.extract("<r><item/></r>",
                XmlLimits.builder().maxDepth(1).build(), grouped));
    }

    @Test
    void oversizedValuesDoNotReachBindings() {
        final XmlMapping<Void> plan = Xml.mapping(Object::new)
                .text("/r/v", (d, v) -> fail("over-budget text must not reach binding")).build(d -> null);
        assertThrows(XmlException.class, () -> Xml.extract(DOCUMENT,
                XmlLimits.builder().maxTextBytes(1).build(), plan));
    }

    @Test
    void partialCdataIsRejectedBeforeTheStreamWindowKeepsGrowing() {
        final byte[] bytes = ("<r><v><![CDATA[" + "x".repeat(100_000) + "]]></v></r>")
                .getBytes(StandardCharsets.UTF_8);
        final CountingStream input = new CountingStream(bytes, 1);
        final XmlMappingEngine<String> engine = new XmlMappingEngine<>(MAPPING, 16);
        engine.limits = XmlLimits.builder().maxTextBytes(4).build();
        assertThrows(XmlException.class, () -> engine.extract(input));
        assertTrue(input.consumed() < 1024, "reject available oversized content before requesting more input");
        assertNull(engine.src);
        assertNull(engine.b);
        assertEquals("ok", engine.extract(DOCUMENT));
    }

    @Test
    void fullyTraversedMappingPathsObeyTheDepthLimitBeforeCallbacks() {
        final XmlMapping<String> nested = Xml.mapping(() -> new String[1])
                .text("/r/a/b/v", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
        final String xml = "<r><a><b><v>ok</v></b></a></r>";
        assertEquals("ok", Xml.extract(xml, XmlLimits.builder().maxDepth(4).build(), nested));
        assertThrows(XmlException.class, () -> Xml.extract(xml, XmlLimits.builder().maxDepth(3).build(), nested));
    }

    @Test
    void reentrantCursorExtractionsKeepTheOuterLimitAndCounters() {
        final XmlLimits two = XmlLimits.builder().maxElements(2).build();
        assertEquals("ok", Xml.extract(DOCUMENT, two, d -> d.child("r", r -> {
            assertThrows(XmlException.class, () -> Xml.extract(DOCUMENT,
                    XmlLimits.builder().maxElements(1).build(), VALUE));
            return r.value("v", String.class);
        })));
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>ok</v><extra/></r>", two, d -> d.child("r", r -> {
            assertEquals("ok", Xml.extract(DOCUMENT, VALUE));
            return r.value("v", String.class);
        })));
    }

    @Test
    void inputLookaheadPreservesAnIoFailureAndReleasesTheSource() {
        final IOException failure = new IOException("lookahead failed");
        final InputStream input = new ByteArrayInputStream(new byte[]{'<', 'r', '/', '>'});
        final InputStream checked = new InputStream() {
            @Override public int read(byte[] b, int off, int len) throws IOException { return input.read(b, off, len); }
            @Override public int read() throws IOException { throw failure; }
        };
        final XmlCursorEngine engine = new XmlCursorEngine();
        engine.limits = XmlLimits.builder().maxInputBytes(4).build();
        assertSame(failure, assertThrows(XmlException.class, () -> engine.extract(checked, d -> null)).getCause());
        assertNull(engine.src);
        assertNull(engine.b);
        assertEquals("ok", engine.extract(DOCUMENT, VALUE));
    }

    @ParameterizedTest @ValueSource(strings = {"ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE"})
    void legacyInputLimitsCountOriginalBytesBeforeTranscoding(final String encoding) {
        final String xml = "<?xml version='1.0' encoding='" + encoding + "'?><r><v>é</v></r>";
        final byte[] bytes = xml.getBytes(Charset.forName(encoding));
        final XmlLimits exact = XmlLimits.builder().maxInputBytes(bytes.length).build();
        final XmlLimits shortLimit = XmlLimits.builder().maxInputBytes(bytes.length - 1).build();
        assertEquals("é", Xml.extract(bytes, exact, MAPPING));
        assertEquals("é", Xml.extract(new CountingStream(bytes, 1), exact, MAPPING));
        assertEquals("é", Xml.extract(new CountingStream(bytes, 1), exact, VALUE));
        assertThrows(XmlException.class, () -> Xml.extract(bytes, shortLimit, MAPPING));
        assertThrows(XmlException.class, () -> Xml.extract(new CountingStream(bytes, 1), shortLimit, MAPPING));
        assertThrows(XmlException.class, () -> Xml.extract(new CountingStream(bytes, 1), shortLimit, VALUE));
    }

    @ParameterizedTest @ValueSource(strings = {"é", "世", "😀", "a\uD800b", "a\uDC00b"})
    void stringInputBudgetMatchesTheJdkUtf8Encoding(final String text) {
        final String xml = "<r><v>" + text + "</v></r>";
        final byte[] encoded = xml.getBytes(StandardCharsets.UTF_8);
        final XmlLimits exact = XmlLimits.builder().maxInputBytes(encoded.length).build();
        assertEquals(Xml.extract(encoded, MAPPING), Xml.extract(xml, exact, MAPPING));
        assertThrows(XmlException.class, () -> Xml.extract(xml,
                XmlLimits.builder().maxInputBytes(encoded.length - 1).build(), MAPPING));
    }

    @Test
    void streamsConsumeAtMostOneByteBeyondTheirBudgetAndStayOpen() {
        final byte[] bytes = ("<r><v>" + "x".repeat(100_000) + "</v></r>").getBytes(StandardCharsets.UTF_8);
        final XmlLimits limits = XmlLimits.builder().maxInputBytes(70001).build();
        final CountingStream mappingInput = new CountingStream(bytes, 97);
        assertThrows(XmlException.class, () -> Xml.extract(mappingInput, limits, MAPPING));
        assertEquals(70002, mappingInput.consumed());
        assertFalse(mappingInput.closed);
        final CountingStream cursorInput = new CountingStream(bytes, 97);
        assertThrows(XmlException.class, () -> Xml.extract(cursorInput, limits, VALUE));
        assertEquals(70002, cursorInput.consumed());
        assertFalse(cursorInput.closed);
    }

    @Test
    void earlyExitDoesNotProbeBeyondTheMappingInputBudget() {
        final XmlMapping<String> early = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.asString()).required("/r/v").build(d -> d[0]);
        final byte[] bytes = "<r><v>ok</v><unread/>".getBytes(StandardCharsets.UTF_8);
        final XmlLimits limits = XmlLimits.builder().maxInputBytes(12).maxElements(2).build();
        final CountingStream input = new CountingStream(bytes, 1);
        assertEquals("ok", Xml.extract(input, limits, early));
        assertEquals(12, input.consumed());
        assertFalse(input.closed);
        assertThrows(XmlException.class, () -> Xml.extract(bytes, limits, early));
    }

    @Test
    void smallBudgetsSurviveReusingAnEngineWithALargerWindow() {
        assertEquals("ok", Xml.extract(new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)), MAPPING));
        final CountingStream input = new CountingStream(DOCUMENT.getBytes(StandardCharsets.UTF_8), 100);
        assertEquals("ok", Xml.extract(input, XmlLimits.builder().maxInputBytes(16).build(), MAPPING));
        assertEquals(16, input.consumed());
    }

    @Test
    void aFailedNullInputDoesNotLeaveLimitsInThePool() {
        final XmlLimits limits = XmlLimits.builder().maxTextBytes(0).build();
        assertThrows(NullPointerException.class, () -> Xml.extract((byte[]) null, limits, MAPPING));
        assertThrows(NullPointerException.class, () -> Xml.extract((byte[]) null, limits, VALUE));
        assertEquals("ok", Xml.extract(DOCUMENT, MAPPING));
        assertEquals("ok", Xml.extract(DOCUMENT, VALUE));
        assertThrows(NullPointerException.class, () -> Xml.extract(DOCUMENT, null, VALUE));
        assertThrows(NullPointerException.class, () -> Xml.extract(DOCUMENT, null, MAPPING));
    }

    @Test
    void concurrentExtractionsKeepDifferentLimitsIsolated() throws Exception {
        final var workers = Executors.newFixedThreadPool(8);
        try {
            final List<Future<?>> jobs = Stream.<Future<?>>generate(() -> workers.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    assertThrows(XmlException.class, () -> Xml.extract(DOCUMENT,
                            XmlLimits.builder().maxElements(1).build(), MAPPING));
                    assertEquals("ok", Xml.extract(DOCUMENT, XmlLimits.builder().maxElements(2).build(), MAPPING));
                }
            })).limit(8).toList();
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void builderRejectsInvalidLimitsAndBuildsIndependentSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxInputBytes(0));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxDepth(0));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxElements(-1));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxNameBytes(0));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxAttributesPerElement(-1));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxAttributesPerElement(1025));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxTextBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> XmlLimits.builder().maxTextBytes(16 * 1024 * 1024 + 1));
        final var builder = XmlLimits.builder().maxDepth(3);
        final XmlLimits first = builder.build();
        builder.maxDepth(9);
        assertEquals(3, first.maxDepth());
        assertEquals(9, builder.build().maxDepth());
        assertEquals(Integer.MAX_VALUE, XmlLimits.defaults().maxDepth());
    }

    private static final class CountingStream extends ByteArrayInputStream {
        private final int chunk;
        boolean closed;

        CountingStream(final byte[] bytes, final int chunk) { super(bytes); this.chunk = chunk; }
        @Override public synchronized int read(final byte[] b, final int off, final int len) {
            return super.read(b, off, Math.min(chunk, len));
        }
        int consumed() { return pos; }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
