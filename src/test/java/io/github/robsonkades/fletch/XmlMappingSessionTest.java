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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class XmlMappingSessionTest {
    private static final String XML = "<r><v>ok</v></r>";
    private static final XmlMapping<String> VALUE = Xml.mapping(() -> new String[1])
            .text("/r/v", (d, v) -> d[0] = v.asString()).build(d -> d[0]);

    enum Source {
        BYTES, STRING, STREAM;

        <T> T extract(final XmlMappingSession<T> session, final String xml) {
            return switch (this) {
                case BYTES -> session.extract(xml == null ? (byte[]) null : xml.getBytes(StandardCharsets.UTF_8));
                case STRING -> session.extract(xml);
                case STREAM -> session.extract(xml == null ? (InputStream) null
                        : new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            };
        }
    }

    @ParameterizedTest @EnumSource(Source.class)
    void eachDocumentHasAFreshDraftAndPreviousResultsRemainValid(final Source source) {
        final var mapping = Xml.mapping(ArrayList<String>::new)
                .text("/r/v", (d, v) -> d.add(v.asString())).build(d -> d);
        try (var session = mapping.openSession()) {
            final var first = source.extract(session, "<r><v>São Paulo &amp; 東京 😀</v><v>two</v></r>");
            final var second = source.extract(session, "<r><v>next</v></r>");
            assertEquals(List.of("São Paulo & 東京 😀", "two"), first);
            assertEquals(List.of("next"), second);
            assertNotSame(first, second);
            assertEquals(List.of(), source.extract(session, "<r/>"));
        }
    }

    @ParameterizedTest @EnumSource(Source.class)
    void limitsAreReappliedAfterSuccessFailureAndNullInput(final Source source) {
        record Budget(XmlLimits limits, String rejected) {}
        final List<Budget> budgets = List.of(
                new Budget(XmlLimits.builder().maxInputBytes(16).build(), " " + XML),
                new Budget(XmlLimits.builder().maxDepth(2).build(), "<r><x><y/></x><v>ok</v></r>"),
                new Budget(XmlLimits.builder().maxElements(2).build(), "<r><x/><v>ok</v></r>"),
                new Budget(XmlLimits.builder().maxNameBytes(1).build(), "<r><xx/><v>ok</v></r>"),
                new Budget(XmlLimits.builder().maxAttributesPerElement(0).build(), "<r a=''><v>ok</v></r>"),
                new Budget(XmlLimits.builder().maxTextBytes(2).build(), "<r><v>bad</v></r>"));
        for (Budget budget : budgets) {
            try (var session = VALUE.openSession(budget.limits())) {
                assertEquals("ok", source.extract(session, XML));
                assertThrows(XmlException.class, () -> source.extract(session, budget.rejected()), budget.rejected());
                assertThrows(NullPointerException.class, () -> source.extract(session, null));
                assertThrows(XmlException.class, () -> source.extract(session, budget.rejected()), budget.rejected());
                assertEquals("ok", source.extract(session, XML));
            }
        }
    }

    @ParameterizedTest @EnumSource(Source.class)
    void parsingAndConversionFailuresDoNotPoisonTheSession(final Source source) {
        final var mapping = Xml.mapping(() -> new int[1])
                .text("/r/v", (d, v) -> d[0] = v.asInt()).build(d -> d[0]);
        try (var session = mapping.openSession()) {
            assertThrows(XmlException.class, () -> source.extract(session, "<r><v>1</wrong></r>"));
            assertThrows(NumberFormatException.class, () -> source.extract(session, "<r><v>bad</v></r>"));
            assertEquals(7, source.extract(session, "<r><v>7</v></r>"));
        }
    }

    enum Callback { SUPPLIER, BINDING, FINISHER }

    @ParameterizedTest @EnumSource(Callback.class)
    void callbackFailuresPropagateAndReleaseDraftsAndInput(final Callback stage) throws Exception {
        final AssertionError failure = new AssertionError("callback failure");
        final AtomicReference<Callback> failing = new AtomicReference<>(stage);
        final var mapping = Xml.mapping(() -> {
            if (failing.get() == Callback.SUPPLIER) throw failure;
            return new String[1];
        }).text("/r/v", (d, v) -> {
            if (failing.get() == Callback.BINDING) throw failure;
            d[0] = v.asString();
        }).build(d -> {
            if (failing.get() == Callback.FINISHER) throw failure;
            return d[0];
        });
        try (var session = mapping.openSession()) {
            assertSame(failure, assertThrows(AssertionError.class, () -> session.extract(XML)));
            assertReleased(session);
            failing.set(null);
            assertEquals("ok", session.extract(XML));
            assertReleased(session);
        }
    }

    @ParameterizedTest @EnumSource(Callback.class)
    void callbacksCannotReenterOrCloseTheirActiveSession(final Callback stage) {
        final AtomicReference<XmlMappingSession<String>> reference = new AtomicReference<>();
        final Runnable misuse = () -> {
            for (Source source : Source.values()) {
                assertThrows(IllegalStateException.class, () -> source.extract(reference.get(), XML));
            }
            assertThrows(IllegalStateException.class, () -> reference.get().close());
        };
        final var mapping = Xml.mapping(() -> {
            if (stage == Callback.SUPPLIER) misuse.run();
            return new String[1];
        }).text("/r/v", (d, v) -> {
            if (stage == Callback.BINDING) misuse.run();
            d[0] = v.asString();
        }).build(d -> {
            if (stage == Callback.FINISHER) misuse.run();
            return d[0];
        });
        try (var session = mapping.openSession()) {
            reference.set(session);
            assertEquals("ok", session.extract(XML));
            assertEquals("next", session.extract("<r><v>next</v></r>"));
        }
    }

    @Test
    void aCallbackCanUseTheSameMappingThroughThePoolOrAnotherSession() {
        final AtomicReference<XmlMapping<Integer>> reference = new AtomicReference<>();
        final var mapping = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> {
            if (v.asInt() == 0) { d[0] = 7; return; }
            try (var nested = reference.get().openSession()) {
                d[0] = nested.extract("<r><v>0</v></r>")
                        + Xml.extract("<r><v>0</v></r>", reference.get()) + v.asInt();
            }
        }).build(d -> d[0]);
        reference.set(mapping);
        try (var session = mapping.openSession()) {
            assertEquals(19, session.extract("<r><v>5</v></r>"));
        }
    }

    @Test
    void closeDetachesTheEngineAndRejectsAllExtractionForms() throws Exception {
        final var session = VALUE.openSession();
        assertEquals("ok", session.extract(XML));
        session.close();
        session.close();
        assertNull(field(session, "engine"));
        for (Source source : Source.values()) {
            assertThrows(IllegalStateException.class, () -> source.extract(session, XML));
            assertThrows(IllegalStateException.class, () -> source.extract(session, null));
        }
    }

    @Test
    void openingWithNullLimitsIsRejected() {
        assertThrows(NullPointerException.class, () -> VALUE.openSession(null));
    }

    @Test
    void streamFailuresPreserveTheCauseAndNeverCloseTheBorrowedStream() throws Exception {
        final IOException failure = new IOException("broken stream");
        final class BrokenStream extends InputStream {
            boolean closed;
            @Override public int read() throws IOException { throw failure; }
            @Override public void close() { closed = true; }
        }
        final BrokenStream input = new BrokenStream();
        try (var session = VALUE.openSession()) {
            assertSame(failure, assertThrows(XmlException.class, () -> session.extract(input)).getCause());
            assertReleased(session);
            assertEquals("ok", session.extract(XML));
        }
        assertFalse(input.closed);
    }

    @Test
    void streamEarlyExitKeepsTheInputOpenAndResetsRequiredFields() throws Exception {
        final var mapping = Xml.mapping(() -> new String[1])
                .text("/r/v", (d, v) -> d[0] = v.asString()).required("/r/v").build(d -> d[0]);
        final class TrackedStream extends ByteArrayInputStream {
            boolean closed;
            TrackedStream() { super((XML + " ".repeat(100_000)).getBytes(StandardCharsets.UTF_8)); }
            @Override public void close() { closed = true; }
        }
        final TrackedStream input = new TrackedStream();
        try (var session = mapping.openSession(XmlLimits.builder().maxInputBytes(1024).build())) {
            assertEquals("ok", session.extract(input));
            assertTrue(input.available() > 0);
            assertReleased(session);
            assertEquals("next", session.extract("<r><v>next</v></r>"));
        }
        assertFalse(input.closed);
    }

    @ParameterizedTest @ValueSource(strings = {"UTF-8", "ISO-8859-1", "UTF-16"})
    void aSessionCanAlternateEncodedArraysStreamsAndDecodedStrings(final String encoding) {
        final byte[] xml = ("<?xml version='1.0' encoding='" + encoding + "'?><r><v>café</v></r>")
                .getBytes(Charset.forName(encoding));
        try (var session = VALUE.openSession()) {
            assertEquals("café", session.extract(xml));
            assertEquals("café", session.extract(new ByteArrayInputStream(xml)));
            assertEquals("ok", session.extract(XML));
        }
    }

    @Test
    void outlierStreamBuffersAreTrimmedBetweenDocuments() throws Exception {
        try (var session = VALUE.openSession()) {
            final String value = "x".repeat(2 * 1024 * 1024);
            assertEquals(value, session.extract(new ByteArrayInputStream(("<r><v>" + value + "</v></r>")
                    .getBytes(StandardCharsets.UTF_8))));
            assertNull(((XmlMappingEngine<?>) field(session, "engine")).io);
            assertEquals("ok", session.extract(XML));
            assertReleased(session);
        }
    }

    @Test
    void strictSkipStillChecksIgnoredEndTags() {
        final var mapping = Xml.mapping(() -> new String[1]).strictSkip()
                .text("/r/v", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
        try (var session = mapping.openSession()) {
            assertThrows(XmlException.class, () -> session.extract("<r><x></y><v>bad</v></r>"));
            assertEquals("ok", session.extract(XML));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anotherThreadCannotExtractOrCloseEvenWhileTheOwnerIsInACallback(final boolean active) throws Exception {
        final var worker = Executors.newSingleThreadExecutor();
        final AtomicReference<XmlMappingSession<String>> reference = new AtomicReference<>();
        final Runnable foreignCalls = () -> {
            try {
                worker.submit(() -> {
                    for (Source source : Source.values()) {
                        assertThrows(IllegalStateException.class, () -> source.extract(reference.get(), XML));
                    }
                    assertThrows(IllegalStateException.class, () -> reference.get().close());
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        final var mapping = Xml.mapping(() -> new String[1]).text("/r/v", (d, v) -> {
            if (active) foreignCalls.run();
            d[0] = v.asString();
        }).build(d -> d[0]);
        try (var session = mapping.openSession()) {
            reference.set(session);
            if (!active) foreignCalls.run();
            assertEquals("ok", session.extract(XML));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void workersSharingAMappingKeepOverlappingSessionDocumentsIsolated() throws Exception {
        final int count = 4;
        final CyclicBarrier overlap = new CyclicBarrier(count);
        final var mapping = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> {
            try { overlap.await(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
            d[0] = v.asInt();
        }).build(d -> d[0]);
        final var workers = Executors.newFixedThreadPool(count);
        try {
            final List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < count; worker++) {
                final int id = worker;
                jobs.add(workers.submit(() -> {
                    try (var session = mapping.openSession(XmlLimits.builder().maxElements(2).build())) {
                        for (int i = 0; i < 50; i++) {
                            final int expected = id * 1000 + i;
                            assertEquals(expected, session.extract("<r><v>" + expected + "</v></r>"));
                        }
                    }
                }));
            }
            for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    // Explicit reference checks test retention without relying on GC timing.
    private static void assertReleased(final XmlMappingSession<?> session) throws Exception {
        final XmlMappingEngine<?> engine = (XmlMappingEngine<?>) field(session, "engine");
        assertNull(engine.src, "stream");
        assertNull(engine.b, "input array");
        assertNull(engine.valA, "selected text");
        assertNull(field(field(engine, "val"), "a"), "binding value");
        for (Object draft : (Object[]) field(engine, "drafts")) assertNull(draft, "draft");
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
