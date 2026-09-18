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

import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Validation of unselected attributes, with rotating prebuilt documents. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AttributeValidationBenchmark {
    @Param({"0", "4", "8", "9", "48", "256"})
    public int attributes;

    @Param({"SHORT", "LONG_PREFIX"})
    public String names;

    private XmlMapping<int[]> mapping;
    private byte[][] documents;
    private int index;

    @Setup
    public void setup() {
        mapping = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> d[0] = v.asInt()).build(d -> d);
        documents = new byte[16][];
        final var order = new ArrayList<Integer>();
        for (int i = 0; i < attributes; i++) order.add(i);
        final Random random = new Random(20260917);
        for (int doc = 0; doc < documents.length; doc++) {
            Collections.shuffle(order, random);
            final StringBuilder xml = new StringBuilder("<r");
            for (int field : order) {
                xml.append(' ').append(names.equals("SHORT") ? "f" : "abcdefghijklmnop_é_")
                        .append(1000 + field).append("='").append(doc + field).append('\'');
            }
            xml.append("><v>").append(doc + 1).append("</v></r>");
            documents[doc] = xml.toString().getBytes(StandardCharsets.UTF_8);
            final int[] result = Xml.extract(documents[doc], mapping);
            if (result.length != 1 || result[0] != doc + 1) throw new IllegalStateException("Fixture differs");
        }
    }

    @Benchmark
    public int[] dynamic() {
        final byte[] document = documents[index];
        index = (index + 1) & 15;
        return Xml.extract(document, mapping);
    }
}
