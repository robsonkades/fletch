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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class XmlAttributeDuplicatesTest {
    private static final XmlExtractor<String> VALUE = d -> d.child("r", r -> r.value("v", String.class));

    private static XmlMapping<String> mapping() {
        return Xml.mapping(() -> new String[1]).strictSkip()
                .text("/r/v", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8, 9, 16, 17, 32, 33, 64, 65, 256, 257, 1024})
    void distinctLongNamesWithTheSamePrefixRemainValid(final int count) {
        final String xml = "<r" + attributes(count) + "><v>ok</v></r>";
        assertEquals("ok", Xml.extract(xml, mapping()));
        assertEquals("ok", Xml.extract(xml, VALUE));
    }

    @ParameterizedTest
    @CsvSource({"8,0", "9,7", "16,0", "17,8", "32,15", "33,16", "64,63",
            "65,32", "256,127", "257,128", "1023,1022"})
    void duplicatesAreRejectedAtBothEndsAndInsideWideTags(final int count, final int repeated) {
        final String prefix = "<r" + attributes(count) + " ";
        final String xml = prefix + name(repeated) + "='again'><v>ok</v></r>";
        final String message = "Duplicate attribute (byte offset "
                + prefix.getBytes(StandardCharsets.UTF_8).length + ")";
        assertEquals(message, assertThrows(XmlException.class, () -> Xml.extract(xml, mapping())).getMessage());
        assertEquals(message, assertThrows(XmlException.class, () -> Xml.extract(xml, VALUE)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 17, 65})
    void partialStartTagsCanBeRetriedAfterWindowCompaction(final int count) {
        final XmlMappingEngine<String> engine = new XmlMappingEngine<>(mapping(), 16);
        final String prefix = "<r>" + " ".repeat(257) + "<ignored" + attributes(count);
        final byte[] valid = bytes(prefix + "/><v>ok</v></r>");
        final byte[] invalid = bytes(prefix + " " + name(0) + "='again'/><v>ok</v></r>");
        for (int seed = 0; seed < 3; seed++) {
            final int s = seed;
            assertEquals("ok", engine.extract(new XmlMappingTest.Drip(valid, seed)), "seed " + seed);
            assertTrue(assertThrows(XmlException.class,
                    () -> engine.extract(new XmlMappingTest.Drip(invalid, s)), "seed " + seed)
                    .getMessage().startsWith("Duplicate attribute"));
            assertEquals("ok", engine.extract(new XmlMappingTest.Drip(valid, seed)), "after failure, seed " + seed);
        }
    }

    @Test
    void repeatedNamesOnDifferentElementsAndDocumentsDoNotLeakState() {
        final XmlMappingEngine<String> engine = mapping().newEngine();
        final XmlCursorEngine cursor = new XmlCursorEngine();
        for (int count : new int[] {1024, 9, 65, 1, 17, 0, 9}) {
            final String attrs = attributes(count);
            final String xml = "<r" + attrs + "><ignored" + attrs + "/><v" + attrs + ">ok</v></r>";
            assertEquals("ok", engine.extract(xml), "count " + count);
            assertEquals("ok", cursor.extract(xml, VALUE), "count " + count);
        }
    }

    @Test
    void aWideFailedDocumentDoesNotPoisonTheNextDocument() {
        final XmlMappingEngine<String> engine = mapping().newEngine();
        final XmlCursorEngine cursor = new XmlCursorEngine();
        final String bad = "<r" + attributes(65) + " " + name(32) + "='again'><v>bad</v></r>";
        final String good = "<r" + attributes(17) + "><v>ok</v></r>";
        assertThrows(XmlException.class, () -> engine.extract(bad));
        assertThrows(XmlException.class, () -> cursor.extract(bad, VALUE));
        assertEquals("ok", engine.extract(good));
        assertEquals("ok", cursor.extract(good, VALUE));
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 17, 65, 1024})
    void configuredAttributeLimitStillAppliesToWideTags(final int count) {
        final XmlLimits limits = XmlLimits.builder().maxAttributesPerElement(count).build();
        final String prefix = "<r" + attributes(count);
        final String good = prefix + "><v>ok</v></r>";
        final String bad = prefix + " extra='x'><v>ok</v></r>";
        final XmlMapping<String> plan = mapping();
        assertEquals("ok", Xml.extract(good, limits, plan));
        assertEquals("ok", Xml.extract(good, limits, VALUE));
        assertTrue(assertThrows(XmlException.class, () -> Xml.extract(bad, limits, plan))
                .getMessage().startsWith("Element exceeds " + count + " attributes"));
        assertTrue(assertThrows(XmlException.class, () -> Xml.extract(bad, limits, VALUE))
                .getMessage().startsWith("Element exceeds " + count + " attributes"));
    }

    private static String attributes(final int count) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(' ').append(name(i)).append("='x'");
        return result.toString();
    }

    private static String name(final int index) {
        return "abcdefghijklmnop_é_" + (10000 + index);
    }

    private static byte[] bytes(final String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
