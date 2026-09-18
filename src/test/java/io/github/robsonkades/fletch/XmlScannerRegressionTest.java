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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class XmlScannerRegressionTest {

    private static final String LONG_NAME = "abcdefghijklmnopA";
    private static final XmlExtractor<String> VALUE = d -> d.child("r", r -> r.value("v", String.class));

    private static XmlMapping<String> mapping(final String path) {
        return Xml.mapping(() -> new String[1]).strictSkip()
                .text(path, (d, v) -> d[0] = v.asString()).build(d -> d[0]);
    }

    @Test
    void leafEndTagsMustMatchBeyondTheFirstSixteenBytes() {
        final String xml = "<r><" + LONG_NAME + ">ok</abcdefghijklmnopB></r>";
        assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/r/" + LONG_NAME)));
        assertThrows(XmlException.class,
                () -> Xml.extract(xml, d -> d.child("r", r -> r.value(LONG_NAME, String.class))));
    }

    @Test
    void containerEndTagsMustMatchBeyondTheFirstSixteenBytes() {
        final String xml = "<" + LONG_NAME + "><v>ok</v></abcdefghijklmnopB>";
        assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/" + LONG_NAME + "/v")));
        assertThrows(XmlException.class,
                () -> Xml.extract(xml, d -> d.child(LONG_NAME, r -> r.value("v", String.class))));
    }

    @Test
    void strictSkipVerifiesCompleteNamesAcrossRefills() {
        final XmlMapping<String> plan = mapping("/r/v");
        final String prefix = "<r><v>ok</v><" + LONG_NAME + ">" + " ".repeat(900);
        final byte[] good = bytes(prefix + "</" + LONG_NAME + "></r>");
        final byte[] bad = bytes(prefix + "</abcdefghijklmnopB></r>");
        for (int seed = 0; seed < 20; seed++) {
            final int s = seed;
            assertEquals("ok", new XmlMappingEngine<>(plan, 16).extract(new XmlMappingTest.Drip(good, s)));
            assertThrows(XmlException.class,
                    () -> new XmlMappingEngine<>(plan, 16).extract(new XmlMappingTest.Drip(bad, s)), "seed " + s);
        }
    }

    @Test
    void nestedElementsInExtractedTextMustCloseWithTheirOwnNames() {
        final String xml = "<r><v><a>x</b></v></r>";
        assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/r/v")));
        assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE));
        assertEquals("xy", Xml.extract("<r><v><a>x</a><b>y</b></v></r>", mapping("/r/v")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<!---->", "<!--x-->", "<!--x>y-->", "<![CDATA[x]]>", "<?p ?>"})
    void markupTerminatorsSurviveEveryNearbyWindowBoundary(final String token) {
        final XmlMapping<String> plan = mapping("/r/v");
        for (int padding = 65510; padding < 65540; padding++) {
            final byte[] xml = bytes("<r>" + " ".repeat(padding) + token + "<v>ok</v></r>");
            assertEquals("ok", Xml.extract(new ByteArrayInputStream(xml), plan), "padding " + padding);
        }
    }

    @Test
    void commentsSurviveOneByteReadsAcrossTinyWindows() {
        final byte[] xml = bytes("<r>" + " ".repeat(503) + "<!----><v>ok</v></r>");
        final ByteArrayInputStream drip = new ByteArrayInputStream(xml) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(1, len));
            }
        };
        assertEquals("ok", new XmlMappingEngine<>(mapping("/r/v"), 64).extract(drip));
    }

    @Test
    void oversizedCleanValuesAreRejectedBeforeBindingOrMaterialization() {
        final byte[] xml = bytes("<r><v>" + "x".repeat(ByteScanner.MAX_TEXT + 1) + "</v></r>");
        final XmlMapping<Void> plan = Xml.mapping(Object::new)
                .text("/r/v", (d, v) -> fail("oversized value must not reach a binding"))
                .build(d -> null);
        assertThrows(XmlException.class, () -> Xml.extract(xml, plan));
        assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE));
        assertThrows(XmlException.class, () -> Xml.extract(new ByteArrayInputStream(xml), plan));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void textAtTheLimitIsAcceptedInMemoryAndAcrossRefills(final int delta) {
        final byte[] xml = bytes("<r><v>" + "x".repeat(ByteScanner.MAX_TEXT + delta) + "</v></r>");
        final XmlMapping<Integer> plan = Xml.mapping(() -> new int[1])
                .text("/r/v", (d, v) -> d[0] = v.asString().length()).build(d -> d[0]);
        assertEquals(ByteScanner.MAX_TEXT + delta, Xml.extract(xml, plan));
        assertEquals(ByteScanner.MAX_TEXT + delta, Xml.extract(new ByteArrayInputStream(xml), plan));
    }

    @Test
    void oversizedCleanAttributesAreRejectedBeforeConversion() {
        final byte[] xml = bytes("<r a=\"" + "x".repeat(ByteScanner.MAX_TEXT + 1) + "\"/>");
        final XmlMapping<Void> plan = Xml.mapping(Object::new)
                .attr("/r@a", (d, v) -> fail("oversized attribute must not reach a binding"))
                .build(d -> null);
        assertThrows(XmlException.class, () -> Xml.extract(xml, plan));
        assertThrows(XmlException.class,
                () -> Xml.extract(xml, d -> d.child("r", r -> r.attribute("a", String.class))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000x", "x\u0001", "a]]>b", "&#xFFFF;", "&#xFFFE;", "<!--a--b-->x"})
    void invalidXmlCharactersAndCommentBodiesAreRejected(final String value) {
        final String xml = "<r><v>" + value + "</v></r>";
        assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/r/v")));
        assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE));
    }

    @Test
    void malformedUtf8IsRejectedInsteadOfReplaced() {
        final byte[][] malformed = {
                {(byte) 0xC3, 0x28}, {(byte) 0xC0, (byte) 0xAF}, {(byte) 0x80},
                {(byte) 0xE0, (byte) 0x80, (byte) 0x80}, {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
                {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xF5}, {(byte) 0xE2, (byte) 0x82}
        };
        for (byte[] value : malformed) {
            final byte[] xml = frame("<r><v>", value, "</v></r>");
            assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/r/v")));
            assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE));
            assertThrows(XmlException.class,
                    () -> Xml.extract(new ByteArrayInputStream(xml), mapping("/r/v")));
        }
    }

    @Test
    void selectedAttributesAndCdataRejectInvalidUtf8() {
        final byte[] malformed = {(byte) 0xC3, 0x28};
        final byte[] attr = frame("<r a=\"", malformed, "\"/>");
        final XmlMapping<String> plan = Xml.mapping(() -> new String[1])
                .attr("/r@a", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
        assertThrows(XmlException.class, () -> Xml.extract(attr, plan));
        assertThrows(XmlException.class,
                () -> Xml.extract(attr, d -> d.child("r", r -> r.attribute("a", String.class))));
        final byte[] cdata = frame("<r><v><![CDATA[", malformed, "]]></v></r>");
        assertThrows(XmlException.class, () -> Xml.extract(cdata, mapping("/r/v")));
    }

    @Test
    void unicodeAndEscapedDelimitersSurviveStreamingAndMixedContent() {
        final String value = "é世😀\uD7FF\uE000\uFFFD";
        final XmlMapping<String> plan = mapping("/r/v");
        for (int padding = 470; padding < 520; padding++) {
            final byte[] xml = bytes("<r><v><![CDATA[p]]>" + "x".repeat(padding) + value + "]]&gt;</v></r>");
            final String expected = "p" + "x".repeat(padding) + value + "]]>";
            assertEquals(expected, Xml.extract(xml, plan));
            assertEquals(expected, new XmlMappingEngine<>(plan, 16).extract(new XmlMappingTest.Drip(xml, padding)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=no", "a=\"x\"a=\"y\"", "a=\"x\" a=\"y\"", "a=\"x<y\"", "a", "=\"x\""})
    void malformedAttributesAreRejectedEvenWhenNotSelected(final String attributes) {
        final String xml = "<r " + attributes + "><v>ok</v></r>";
        assertThrows(XmlException.class, () -> Xml.extract(xml, mapping("/r/v")));
        assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE));
    }

    @Test
    void attributeCountIsBoundedBeforeDuplicateChecksGrowWithoutLimit() {
        final StringBuilder tag = new StringBuilder("<r");
        for (int i = 0; i < ByteScanner.MAX_ATTRIBUTES; i++) tag.append(" a").append(i).append("=\"x\"");
        final XmlMapping<String> plan = mapping("/r/v");
        assertEquals("ok", Xml.extract(tag + "><v>ok</v></r>", plan));
        final byte[] oversized = bytes(tag + " extra=\"x\"><v>ok</v></r>");
        assertThrows(XmlException.class, () -> Xml.extract(oversized, plan));
        assertThrows(XmlException.class, () -> Xml.extract(oversized, VALUE));
        assertThrows(XmlException.class, () -> Xml.extract(new ByteArrayInputStream(oversized), plan));
    }

    @Test
    void endTagsAcceptOnlyXmlWhitespace() {
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>x</v\u0000></r>", mapping("/r/v")));
        assertEquals("x", Xml.extract("<r><v>x</v \t\r\n></r>", mapping("/r/v")));
    }

    private static byte[] frame(final String prefix, final byte[] value, final String suffix) {
        final byte[] start = bytes(prefix);
        final byte[] end = bytes(suffix);
        final byte[] xml = new byte[start.length + value.length + end.length];
        System.arraycopy(start, 0, xml, 0, start.length);
        System.arraycopy(value, 0, xml, start.length, value.length);
        System.arraycopy(end, 0, xml, start.length + value.length, end.length);
        return xml;
    }

    private static byte[] bytes(final String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
