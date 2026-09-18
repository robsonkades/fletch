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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XmlEngineLifecycleTest {

    private static XmlMapping<Integer> mapping() {
        return Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> d[0] = v.asString().length())
                .build(d -> d[0]);
    }

    @Test
    void aCompletedMappingDoesNotRetainTheCallerDocument() throws Exception {
        final XmlMapping<Integer> plan = mapping();
        final byte[] xml = "<r><v>ok</v></r>".getBytes(StandardCharsets.UTF_8);
        assertEquals(2, Xml.extract(xml, plan));
        final XmlMappingEngine<?> engine = pooled(plan);
        assertReleased(engine);
        assertNull(field(field(engine, "val"), "a"), "the value flyweight must release its source too");
    }

    @Test
    void failedInitialReadsReleaseTheSourceInBothEngines() {
        final IOException failure = new IOException("broken stream");
        final InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
        };
        final XmlMappingEngine<Integer> mapping = mapping().newEngine();
        assertSame(failure, assertThrows(XmlException.class, () -> mapping.extract(input)).getCause());
        assertReleased(mapping);
        assertEquals(2, mapping.extract("<r><v>ok</v></r>"));

        final XmlCursorEngine cursor = new XmlCursorEngine();
        assertSame(failure, assertThrows(XmlException.class, () -> cursor.extract(input, d -> null)).getCause());
        assertReleased(cursor);
        assertEquals("ok", cursor.extract("<r><v>ok</v></r>", d -> d.child("r", r -> r.value("v", String.class))));
    }

    @Test
    void failedBindingsReleaseTheSourceAndDrafts() throws Exception {
        final IllegalStateException failure = new IllegalStateException("broken binding");
        final XmlMapping<Void> plan = Xml.mapping(Object::new)
                .text("/r/v", (d, v) -> { throw failure; }).build(d -> null);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> Xml.extract("<r><v>ok</v></r>", plan)));
        final XmlMappingEngine<?> engine = pooled(plan);
        assertReleased(engine);
        assertNull(field(field(engine, "val"), "a"));
        for (Object draft : (Object[]) field(engine, "drafts")) assertNull(draft);
    }

    @Test
    void outlierStreamBuffersAreNotRetainedInTheMappingPool() throws Exception {
        final XmlMapping<Integer> plan = mapping();
        final byte[] xml = ("<r><v>" + "x".repeat(2 * 1024 * 1024) + "</v></r>")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(2 * 1024 * 1024, Xml.extract(new ByteArrayInputStream(xml), plan));
        assertNull(pooled(plan).io, "large windows must be dropped before pooling");
        assertEquals(2, Xml.extract(new ByteArrayInputStream("<r><v>ok</v></r>".getBytes(StandardCharsets.UTF_8)), plan));
    }

    @Test
    void reentrantUseOfTheSameMappingPreservesTheOuterValue() {
        final AtomicReference<XmlMapping<Integer>> reference = new AtomicReference<>();
        final XmlMapping<Integer> plan = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> {
            final int nested = v.asInt() == 0 ? 7 : Xml.extract("<r><v>0</v></r>", reference.get());
            d[0] = nested + v.asInt();
        }).build(d -> d[0]);
        reference.set(plan);
        assertEquals(12, Xml.extract("<r><v>5</v></r>", plan));
        assertEquals(16, Xml.extract("<r><v>9</v></r>", plan));
    }

    @Test
    void pooledMappingsKeepConcurrentDocumentsIsolated() throws Exception {
        final XmlMapping<Integer> plan = Xml.mapping(() -> new int[1])
                .text("/r/v", (d, v) -> d[0] = v.asInt()).build(d -> d[0]);
        final var workers = Executors.newFixedThreadPool(16);
        try {
            final List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                final int id = worker;
                jobs.add(workers.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        final int expected = id * 1000 + i;
                        assertEquals(expected, Xml.extract("<r><v>" + expected + "</v></r>", plan));
                    }
                }));
            }
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void assertReleased(final ByteScanner engine) {
        assertNull(engine.src, "stream");
        assertNull(engine.b, "document buffer");
        assertNull(engine.valA, "last text span");
    }

    // Reachability assertions are deterministic; a forced GC would not prove pool hygiene.
    private static XmlMappingEngine<?> pooled(final XmlMapping<?> mapping) throws Exception {
        final AtomicReferenceArray<?> pool = (AtomicReferenceArray<?>) field(mapping, "pool");
        return (XmlMappingEngine<?>) pool.get((int) Thread.currentThread().getId() & 7);
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
