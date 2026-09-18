/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.ThreadParams;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Full NF-e extraction with shared pooling, a session, or a private mapping per worker. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ConcurrentMappingBenchmark {
    @State(Scope.Benchmark)
    public static class Input {
        @Param({"1", "50"})
        public int dets;

        byte[] doc;
        XmlMapping<ExtractionBenchmark.Nfe> mapping;
        ExtractionBenchmark.Nfe expected;

        @Setup
        public void setup() throws IOException {
            final var fixture = new ExtractionBenchmark();
            fixture.dets = dets;
            fixture.setup(); // Includes full-result checks against the cursor and strict mapping.
            doc = fixture.doc;
            expected = fixture.plan();
            mapping = ExtractionBenchmark.nfePlan(false);
        }
    }

    @State(Scope.Thread)
    public static class Worker {
        XmlMapping<ExtractionBenchmark.Nfe> mapping;
        XmlMappingSession<ExtractionBenchmark.Nfe> session;

        @Setup
        public void setup(final Input input, final BenchmarkParams benchmark, final ThreadParams worker) {
            mapping = benchmark.getBenchmark().endsWith(".privatePool")
                    ? ExtractionBenchmark.nfePlan(false) : input.mapping;
            if (benchmark.getBenchmark().endsWith(".session")) session = mapping.openSession();
            verify(input);
            final long id = Thread.currentThread().getId();
            System.out.println("FLETCH_WORKER index=" + worker.getThreadIndex()
                    + " threadId=" + id + " slot=" + (id & 7) + " bytes=" + input.doc.length);
        }

        @TearDown
        public void close(final Input input) {
            try { verify(input); }
            finally { if (session != null) session.close(); }
        }

        private void verify(final Input input) {
            final var actual = session == null ? mapping.extract(input.doc) : session.extract(input.doc);
            if (!input.expected.equals(actual)) throw new IllegalStateException("Worker result differs from fixture");
        }
    }

    @Benchmark
    public ExtractionBenchmark.Nfe sharedPool(final Input input, final Worker worker) {
        return worker.mapping.extract(input.doc);
    }

    @Benchmark
    public ExtractionBenchmark.Nfe session(final Input input, final Worker worker) {
        return worker.session.extract(input.doc);
    }

    /** Diagnostic control: retains pool atomics but removes sharing of its array between workers. */
    @Benchmark
    public ExtractionBenchmark.Nfe privatePool(final Input input, final Worker worker) {
        return worker.mapping.extract(input.doc);
    }
}
