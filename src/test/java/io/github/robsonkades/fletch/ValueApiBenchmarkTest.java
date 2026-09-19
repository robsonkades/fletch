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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueApiBenchmarkTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void everyVariantAndWraparoundHaveTheExpectedResult(final int presentPercent) {
        final ValueApiBenchmark benchmark = new ValueApiBenchmark();
        benchmark.presentPercent = presentPercent;
        final IntFunction<Integer> number = i -> present(i, presentPercent) ? Integer.valueOf(1000 + i * 31) : null;
        final IntFunction<Integer> fallback = i -> present(i, presentPercent) ? Integer.valueOf(1000 + i * 31) : -1;
        final IntFunction<LocalDate> date = i -> present(i, presentPercent) ? LocalDate.of(2026, 9, 1).plusDays(i) : null;
        final IntFunction<LocalDate> extendedDate = i -> present(i, presentPercent) ? LocalDate.of(12026, 9, 1).plusDays(i) : null;
        assertRotation(benchmark, benchmark::nativeInteger, number);
        assertRotation(benchmark, benchmark::customInteger, number);
        assertRotation(benchmark, benchmark::existsThenInteger, number);
        assertRotation(benchmark, benchmark::manualIntegerFallback, fallback);
        assertRotation(benchmark, benchmark::customIntegerFallback, fallback);
        assertRotation(benchmark, benchmark::manualDate, date);
        assertRotation(benchmark, benchmark::typedDate, date);
        assertRotation(benchmark, benchmark::typedExtendedDate, extendedDate);
    }

    private static boolean present(final int index, final int percent) {
        return (index * 37 % 64) < percent * 64 / 100;
    }

    private static <T> void assertRotation(final ValueApiBenchmark benchmark,
                                         final Supplier<ValueApiBenchmark.Read<T>> operation,
                                         final IntFunction<T> value) {
        benchmark.setup();
        for (int i = 0; i < 128; i++) {
            final int index = i % 64;
            assertEquals(new ValueApiBenchmark.Read<>(value.apply(index), "end-" + index), operation.get(),
                    "presence=" + benchmark.presentPercent + ", invocation=" + i);
        }
    }
}
