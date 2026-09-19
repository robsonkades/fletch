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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * One operation extracts one small document and returns the converted value
 * plus a trailing sibling. Inputs rotate through 64 documents, including
 * interspersed missing values when requested. Empty values are excluded:
 * exists checks structural presence, not the availability of nonempty text.
 * No input construction, callback construction or I/O is timed.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class ValueApiBenchmark {
    static final int VARIANTS = 64;

    public record Read<T>(T value, String tail) {}

    private static final XmlExtractor<Read<Integer>> NATIVE_INTEGER = root -> root.child("r", c ->
            new Read<>(c.value("value", Integer.class), c.value("tail", String.class)));

    private static final XmlExtractor<Read<Integer>> CUSTOM_INTEGER = root -> root.child("r", c ->
            new Read<>(c.valueWith("value", Integer::valueOf), c.value("tail", String.class)));

    private static final XmlExtractor<Read<Integer>> EXISTS_THEN_INTEGER = root -> root.child("r", c -> {
        final Integer value = c.exists("value") ? c.value("value", Integer.class) : null;
        return new Read<>(value, c.value("tail", String.class));
    });

    private static final XmlExtractor<Read<Integer>> MANUAL_INTEGER_FALLBACK = root -> root.child("r", c -> {
        final String text = c.value("value", String.class);
        final Integer value = text == null ? Integer.valueOf(-1) : Integer.valueOf(text);
        return new Read<>(value, c.value("tail", String.class));
    });

    private static final XmlExtractor<Read<Integer>> CUSTOM_INTEGER_FALLBACK = root -> root.child("r", c ->
            new Read<>(c.valueWith("value", Integer::valueOf, () -> -1), c.value("tail", String.class)));

    private static final XmlExtractor<Read<LocalDate>> MANUAL_DATE = root -> root.child("r", c -> {
        final String text = c.value("value", String.class);
        return new Read<>(text == null ? null : LocalDate.parse(text), c.value("tail", String.class));
    });

    private static final XmlExtractor<Read<LocalDate>> TYPED_DATE = root -> root.child("r", c ->
            new Read<>(c.value("value", LocalDate.class), c.value("tail", String.class)));

    @Param({"50", "100"})
    public int presentPercent;

    private byte[][] integers;
    private byte[][] dates;
    private int position;

    @Setup
    public void setup() {
        if (presentPercent != 0 && presentPercent != 50 && presentPercent != 100) {
            throw new IllegalArgumentException("Supported presence mixes: 0, 50, 100");
        }
        integers = new byte[VARIANTS][];
        dates = new byte[VARIANTS][];
        for (int i = 0; i < VARIANTS; i++) {
            final boolean present = ((i * 37) & (VARIANTS - 1)) < VARIANTS * presentPercent / 100;
            integers[i] = document(i, present ? Integer.toString(1000 + i * 31) : null);
            dates[i] = document(i, present ? LocalDate.of(2026, 9, 1).plusDays(i).toString() : null);
        }
        position = 0;
    }

    private static byte[] document(final int index, final String value) {
        return ("<r>" + (value == null ? "" : "<value>" + value + "</value>")
                + "<tail>end-" + index + "</tail></r>").getBytes(StandardCharsets.UTF_8);
    }

    private int next() {
        final int index = position;
        position = (index + 1) & (VARIANTS - 1);
        return index;
    }

    @Benchmark
    public Read<Integer> nativeInteger() {
        return Xml.extract(integers[next()], NATIVE_INTEGER);
    }

    @Benchmark
    public Read<Integer> customInteger() {
        return Xml.extract(integers[next()], CUSTOM_INTEGER);
    }

    @Benchmark
    public Read<Integer> existsThenInteger() {
        return Xml.extract(integers[next()], EXISTS_THEN_INTEGER);
    }

    @Benchmark
    public Read<Integer> manualIntegerFallback() {
        return Xml.extract(integers[next()], MANUAL_INTEGER_FALLBACK);
    }

    @Benchmark
    public Read<Integer> customIntegerFallback() {
        return Xml.extract(integers[next()], CUSTOM_INTEGER_FALLBACK);
    }

    @Benchmark
    public Read<LocalDate> manualDate() {
        return Xml.extract(dates[next()], MANUAL_DATE);
    }

    @Benchmark
    public Read<LocalDate> typedDate() {
        return Xml.extract(dates[next()], TYPED_DATE);
    }
}
