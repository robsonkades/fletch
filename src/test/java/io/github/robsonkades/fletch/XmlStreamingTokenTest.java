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

class XmlStreamingTokenTest {
    private static final String PAD = " ".repeat(501);
    private static final String SPACE = " \t\r\n".repeat(512);

    private static XmlMapping<String> mapping(final String path, final boolean strict) {
        final var builder = Xml.mapping(() -> new String[1])
                .text(path, (d, v) -> d[0] = v.asString());
        if (strict) builder.strictSkip();
        return builder.build(d -> d[0]);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 64})
    void anchoredTagsKeepTheirMeaningAcrossShortReadsAndCompaction(final int chunk) {
        final String name = "n".repeat(2048);
        final String attr = "a".repeat(2048);
        final String attributes = " " + attr + SPACE + "=" + SPACE + "'x>y' id='7'";
        final String[] documents = {
                "<r" + SPACE + "><v>ok</v></r" + SPACE + ">",
                "<r>" + PAD + "<v" + attributes + ">ok</v" + SPACE + "></r>",
                "<r>" + PAD + "<v><" + name + attributes + ">ok</" + name + SPACE + "></v></r>",
                "<r>" + PAD + "<skip><" + name + attributes + "/></skip" + SPACE + "><v>ok</v></r>"
        };
        for (boolean strict : new boolean[] {false, true}) {
            final XmlMapping<String> plan = mapping("/r/v", strict);
            for (String xml : documents) {
                assertEquals("ok", new XmlMappingEngine<>(plan, 32).extract(drip(xml, chunk)));
            }
        }
        final String xml = "<r>" + PAD + "<" + name + SPACE + ">ok</" + name + SPACE + "></r>";
        assertEquals("ok", new XmlMappingEngine<>(mapping("/r/" + name, true), 32).extract(drip(xml, chunk)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7})
    void selectedCdataResumesWithEnoughTailForASplitTerminator(final int chunk) {
        final String content = "x]>".repeat(1024) + "]";
        final String xml = "<r>" + PAD + "<v><![CDATA[" + content + "]]><![CDATA[z]]></v></r>";
        assertEquals(content + "z", new XmlMappingEngine<>(mapping("/r/v", true), 32).extract(drip(xml, chunk)));
    }

    @Test
    void shortReadsPreserveAttributeValuesAndDuplicateChecksAfterCompaction() {
        final StringBuilder attributes = new StringBuilder();
        for (int i = 0; i < 40; i++) attributes.append(" a").append(i).append("='x>y&amp;z'");
        final XmlMapping<String> plan = Xml.mapping(() -> new String[1])
                .attr("/r/v@a39", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
        for (int padding : new int[] {0, 100, 501}) {
            // With padding 100, compaction moves both completed attribute spans
            // and their duplicate index while a later attribute is incomplete.
            final String prefix = "<r>" + " ".repeat(padding) + "<v" + attributes;
            final XmlMappingEngine<String> engine = new XmlMappingEngine<>(plan, 32);
            engine.limits = XmlLimits.builder().maxAttributesPerElement(40).build();
            assertEquals("x>y&z", engine.extract(drip(prefix + "/></r>", 1)));
            final XmlException duplicate = assertThrows(XmlException.class,
                    () -> engine.extract(drip(prefix + " a0='duplicate'/></r>", 1)));
            assertTrue(duplicate.getMessage().contains("Duplicate attribute"));
            engine.limits = XmlLimits.builder().maxAttributesPerElement(39).build();
            final XmlException count = assertThrows(XmlException.class,
                    () -> engine.extract(drip(prefix + "/></r>", 1)));
            assertTrue(count.getMessage().contains("exceeds 39 attributes"));
        }
    }

    @Test
    void whitespaceTokensRespectExactInputAndStructureLimits() {
        final String xml = "<r" + SPACE + "><v>ok</v></r>";
        final int bytes = xml.getBytes(StandardCharsets.UTF_8).length;
        final XmlLimits limits = XmlLimits.builder().maxInputBytes(bytes).maxDepth(2).maxElements(2)
                .maxNameBytes(1).maxAttributesPerElement(0).maxTextBytes(2).build();
        assertEquals("ok", Xml.extract(drip(xml, 1), limits, mapping("/r/v", true)));
        assertThrows(XmlException.class, () -> Xml.extract(drip(xml, 1),
                XmlLimits.builder().maxInputBytes(bytes - 1).build(), mapping("/r/v", true)));
    }

    @Test
    void partialNamesAndValuesRetainTheirWholeLengthForLimits() {
        final XmlMapping<String> plan = mapping("/r/v", true);
        final XmlLimits limits = XmlLimits.builder().maxNameBytes(512).maxTextBytes(512).build();
        for (String tag : new String[] {"n".repeat(513), "v " + "a".repeat(513) + "='x'",
                "v a='" + "x".repeat(513) + "'"}) {
            assertThrows(XmlException.class,
                    () -> Xml.extract(drip("<r>" + PAD + "<" + tag + "/></r>", 1), limits, plan));
        }
        assertThrows(XmlException.class, () -> Xml.extract(
                drip("<r>" + PAD + "<v><![CDATA[" + "x".repeat(513) + "]]></v></r>", 1), limits, plan));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v ", "v /", "v a", "v a ", "v a=", "v a= ", "v a='", "v a='x",
            "v a='x'bad='y'>", "v a='x<y'>", "v a=no>", "v /x>", "/r ", "v><![CDATA[x"})
    void incompleteOrInvalidTokensDoNotContaminateTheNextSessionExtraction(final String token) {
        try (XmlMappingSession<String> session = mapping("/r/v", true).openSession()) {
            assertThrows(XmlException.class, () -> session.extract(drip("<r>" + PAD + "<" + token, 1)));
            assertEquals("ok", session.extract(drip("<r>" + PAD + "<v a='ok'>ok</v></r>", 1)));
            assertEquals("next", session.extract("<r><v>next</v></r>"));
        }
    }

    private static ByteArrayInputStream drip(final String xml, final int chunk) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(final byte[] b, final int off, final int len) {
                return super.read(b, off, Math.min(chunk, len));
            }
        };
    }
}
