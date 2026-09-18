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

import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Equivalent structured output over a rotating corpus of 16 documents.
 * Construction and mapping compilation are outside timing; encoding a String,
 * creating an InputStream, extraction, conversion, result construction and pool
 * cleanup are inside timing for their respective input forms.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class CorpusBenchmark {
    public enum Shape { PLAIN, UNICODE, REORDERED, ATTRIBUTES, UNSELECTED }
    public enum Source { BYTES, STRING, STREAM }
    public enum Budget { DEFAULT, BOUNDED }

    @Param({"PLAIN", "UNICODE", "REORDERED", "ATTRIBUTES", "UNSELECTED"})
    public Shape shape;

    @Param({"4", "16", "48"})
    public int fanout;

    @Param({"BYTES", "STRING", "STREAM"})
    public Source source;

    @Param({"DEFAULT", "BOUNDED"})
    public Budget budget;

    record Result(String id, List<String> fields) {}

    static final class Draft {
        String id;
        final String[] fields;
        Draft(final int count) { fields = new String[count]; }
        Result finish() { return new Result(id, List.of(fields)); }
    }

    static final int VARIANTS = 16;
    final String[] documents = new String[VARIANTS];
    final byte[][] bytes = new byte[VARIANTS][];
    final Result[] expected = new Result[VARIANTS];
    private XmlMapping<Result> mapping;
    private XmlMappingSession<Result> session;
    private XmlExtractor<Result> cursor;
    private XmlLimits limits;
    private int next;

    @Setup
    public void setup() {
        final String[] names = new String[fanout];
        final var builder = Xml.mapping(() -> new Draft(fanout))
                .attr("/r@id", (d, v) -> d.id = v.asString());
        for (int i = 0; i < fanout; i++) {
            final int field = i;
            names[i] = "field" + i;
            builder.text("/r/" + names[i], (d, v) -> d.fields[field] = v.asString());
        }
        mapping = builder.build(Draft::finish);
        cursor = doc -> doc.child("r", r -> {
            final Draft draft = new Draft(fanout);
            draft.id = r.attribute("id", String.class);
            for (int i = 0; i < fanout; i++) draft.fields[i] = r.value(names[i], String.class);
            return draft.finish();
        });
        limits = budget == Budget.DEFAULT ? XmlLimits.defaults() : XmlLimits.builder()
                .maxInputBytes(1024 * 1024).maxDepth(16).maxElements(1024)
                .maxNameBytes(128).maxAttributesPerElement(64).maxTextBytes(4096).build();
        session = mapping.openSession(limits);
        for (int variant = 0; variant < VARIANTS; variant++) {
            final String id = "document-" + variant;
            final String[] values = new String[fanout];
            for (int i = 0; i < fanout; i++) {
                values[i] = shape == Shape.UNICODE
                        ? "São Paulo 東京 😀 & " + variant + "/" + i
                        : "value-" + variant + "-" + i;
            }
            expected[variant] = new Result(id, List.of(values));
            final StringBuilder xml = new StringBuilder("<r id=\"").append(id).append('"');
            if (shape == Shape.ATTRIBUTES) {
                for (int a = 0; a < 32; a++) xml.append(" ignored").append(a).append("=\"x&amp;y\"");
            }
            xml.append('>');
            for (int position = 0; position < fanout; position++) {
                final int i = shape == Shape.REORDERED ? fanout - position - 1 : position;
                if (shape == Shape.UNSELECTED) {
                    xml.append("<ignored").append(i).append(" a=\"x\"><nested>")
                            .append("unused ".repeat(24)).append("</nested></ignored").append(i).append('>');
                }
                xml.append('<').append(names[i]).append('>')
                        .append(values[i].replace("&", "&amp;"))
                        .append("</").append(names[i]).append('>');
            }
            documents[variant] = xml.append("</r>").toString();
            bytes[variant] = documents[variant].getBytes(StandardCharsets.UTF_8);
            if (!expected[variant].equals(readMapping(variant)) || !expected[variant].equals(readCursor(variant))
                    || !expected[variant].equals(readSession(variant))) {
                throw new IllegalStateException("Corpus result differs: " + shape + "/" + source + "/" + variant);
            }
        }
        next = 0;
    }

    @Benchmark
    public Result mapping() { return readMapping(next++ & (VARIANTS - 1)); }

    @Benchmark
    public Result mappingSession() { return readSession(next++ & (VARIANTS - 1)); }

    @TearDown
    public void close() { session.close(); }

    @Benchmark
    public Result cursor() { return readCursor(next++ & (VARIANTS - 1)); }

    private Result readMapping(final int index) {
        return switch (source) {
            case BYTES -> Xml.extract(bytes[index], limits, mapping);
            case STRING -> Xml.extract(documents[index], limits, mapping);
            case STREAM -> Xml.extract(new ByteArrayInputStream(bytes[index]), limits, mapping);
        };
    }

    private Result readSession(final int index) {
        return switch (source) {
            case BYTES -> session.extract(bytes[index]);
            case STRING -> session.extract(documents[index]);
            case STREAM -> session.extract(new ByteArrayInputStream(bytes[index]));
        };
    }

    private Result readCursor(final int index) {
        return switch (source) {
            case BYTES -> Xml.extract(bytes[index], limits, cursor);
            case STRING -> Xml.extract(documents[index], limits, cursor);
            case STREAM -> Xml.extract(new ByteArrayInputStream(bytes[index]), limits, cursor);
        };
    }
}
