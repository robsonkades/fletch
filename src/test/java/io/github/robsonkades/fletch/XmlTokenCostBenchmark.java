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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded growth cases for short-read token scanning and out-of-order cursor replay. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class XmlTokenCostBenchmark {
    @State(Scope.Thread)
    public static class TokenInput {
        @Param({"2048", "4096", "8192"}) public int size;
        @Param({"1", "16", "65536"}) public int chunk;
        @Param({"start", "end", "name", "attribute", "cdata"}) public String token;
        byte[] xml;
        XmlMapping<String> mapping;
        XmlLimits limits;

        @Setup
        public void setup() {
            String path = "/r/v";
            String expected = "ok";
            final String document;
            switch (token) {
                case "start" -> document = "<r" + " ".repeat(size) + "><v>ok</v></r>";
                case "end" -> document = "<r><v>ok</v" + " ".repeat(size) + "></r>";
                case "name" -> {
                    final String name = "n".repeat(size);
                    path = "/r/" + name;
                    document = "<r><" + name + ">ok</" + name + "></r>";
                }
                case "attribute" -> document = "<r a='" + "x".repeat(size) + "'><v>ok</v></r>";
                case "cdata" -> {
                    expected = "x".repeat(size);
                    document = "<r><v><![CDATA[" + expected + "]]></v></r>";
                }
                default -> throw new IllegalArgumentException(token);
            }
            xml = document.getBytes(StandardCharsets.UTF_8);
            mapping = Xml.mapping(() -> new String[1]).text(path, (d, v) -> d[0] = v.asString()).build(d -> d[0]);
            limits = XmlLimits.builder().maxInputBytes(xml.length).maxDepth(2).maxElements(2).build();
            if (!expected.equals(Xml.extract(new Drip(xml, chunk), limits, mapping))) {
                throw new IllegalStateException("Incorrect streaming fixture result");
            }
        }
    }

    @State(Scope.Thread)
    public static class CursorInput {
        @Param({"128", "512", "2048"}) public int size;
        byte[] replay;
        byte[] ordered;
        XmlLimits limits;
        final XmlExtractor<List<Integer>> extractor = d -> d.child("r", r -> {
            r.child("b", b -> null);
            return r.children("a", a -> 0);
        });

        @Setup
        public void setup() {
            final String siblings = "<a/>".repeat(size);
            replay = ("<r>" + siblings + "<b/></r>").getBytes(StandardCharsets.UTF_8);
            ordered = ("<r><b/>" + siblings + "</r>").getBytes(StandardCharsets.UTF_8);
            limits = XmlLimits.builder().maxInputBytes(replay.length).maxElements(size + 2).maxDepth(2).build();
            for (byte[] xml : new byte[][] {replay, ordered}) {
                final List<Integer> values = Xml.extract(xml, limits, extractor);
                if (values.size() != size || values.stream().anyMatch(v -> v != 0)) {
                    throw new IllegalStateException("Incorrect cursor fixture result");
                }
            }
        }
    }

    @Benchmark
    public String streamingToken(final TokenInput input) {
        return Xml.extract(new Drip(input.xml, input.chunk), input.limits, input.mapping);
    }

    @Benchmark
    public List<Integer> cursorReplay(final CursorInput input) {
        return Xml.extract(input.replay, input.limits, input.extractor);
    }

    @Benchmark
    public List<Integer> cursorInOrder(final CursorInput input) {
        return Xml.extract(input.ordered, input.limits, input.extractor);
    }

    private static final class Drip extends ByteArrayInputStream {
        private final int chunk;

        Drip(final byte[] bytes, final int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override public synchronized int read(final byte[] b, final int off, final int len) {
            return super.read(b, off, Math.min(chunk, len));
        }
    }
}
