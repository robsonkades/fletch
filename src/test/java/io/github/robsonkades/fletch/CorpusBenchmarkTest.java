/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import com.ctc.wstx.stax.WstxInputFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorpusBenchmarkTest {
    @ParameterizedTest @EnumSource(CorpusBenchmark.Shape.class)
    void corpusMatchesWoodstoxAndBothExtractionStyles(final CorpusBenchmark.Shape shape) throws Exception {
        final WstxInputFactory factory = new WstxInputFactory();
        for (int fanout : new int[]{4, 16, 48}) {
            for (CorpusBenchmark.Source source : CorpusBenchmark.Source.values()) {
                for (CorpusBenchmark.Budget budget : CorpusBenchmark.Budget.values()) {
                    final CorpusBenchmark corpus = new CorpusBenchmark();
                    corpus.shape = shape;
                    corpus.fanout = fanout;
                    corpus.source = source;
                    corpus.budget = budget;
                    corpus.setup();
                    try {
                        final String context = shape + "/" + fanout + "/" + source + "/" + budget;
                        for (int v = 0; v < CorpusBenchmark.VARIANTS; v++) {
                            assertEquals(corpus.expected[v], read(factory, corpus.documents[v], fanout), context + "/" + v);
                        }
                        for (int v = 0; v < CorpusBenchmark.VARIANTS * 2; v++) {
                            assertEquals(corpus.expected[v % CorpusBenchmark.VARIANTS], corpus.mapping(), context + "/mapping/" + v);
                        }
                        for (int v = 0; v < CorpusBenchmark.VARIANTS * 2; v++) {
                            assertEquals(corpus.expected[v % CorpusBenchmark.VARIANTS], corpus.cursor(), context + "/cursor/" + v);
                        }
                        for (int v = 0; v < CorpusBenchmark.VARIANTS * 2; v++) {
                            assertEquals(corpus.expected[v % CorpusBenchmark.VARIANTS], corpus.mappingSession(), context + "/session/" + v);
                        }
                    } finally {
                        corpus.close();
                    }
                }
            }
        }
    }

    private static CorpusBenchmark.Result read(final WstxInputFactory factory, final String xml, final int count) throws Exception {
        final XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
        final String[] fields = new String[count];
        String id = null;
        int depth = 0;
        try {
            while (reader.hasNext()) {
                final int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1) id = reader.getAttributeValue(null, "id");
                    if (depth == 2 && reader.getLocalName().startsWith("field")) {
                        final int index = Integer.parseInt(reader.getLocalName().substring(5));
                        fields[index] = reader.getElementText();
                        depth--;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            return new CorpusBenchmark.Result(id, List.of(fields));
        } finally {
            reader.close();
        }
    }
}
