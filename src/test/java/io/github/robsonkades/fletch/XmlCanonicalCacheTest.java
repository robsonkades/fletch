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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class XmlCanonicalCacheTest {
    private static final List<String> STATE_CODES = List.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN",
            "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private static XmlMapping<List<String>> mapping() {
        return XmlMapping.<List<String>>builder(ArrayList::new)
                .text("/r/v", (d, v) -> d.add(v.asCanonical()))
                .attr("/r@code", (d, v) -> d.add(v.asCanonical()))
                .build(d -> d);
    }

    @Test
    void unusedAndOversizedValuesDoNotAllocateTables() throws Exception {
        final var engine = mapping().newEngine();
        assertEquals(List.of(), engine.extract("<r/>"));
        assertNull(field(engine, "canonVal"));
        final String value = "x".repeat(65);
        assertEquals(value, extract(engine, value));
        assertNull(field(engine, "canonVal"));
        assertNotSame(extract(engine, value), extract(engine, value));
    }

    @Test
    void twoStateCodesKeepTheCacheSmall() throws Exception {
        final var engine = mapping().newEngine();
        final String first = extract(engine, "SP");
        final String second = extract(engine, "SC");
        assertSame(first, extract(engine, "SP"));
        assertSame(second, extract(engine, "SC"));
        assertTrue(table(engine).length <= 16, "two values must not allocate the maximum tables");
    }

    @Test
    void allStateCodesFitWithinTheMemoryBudget() throws Exception {
        final var engine = mapping().newEngine();
        for (String code : STATE_CODES) assertEquals(code, extract(engine, code));
        assertEquals(27, Arrays.stream(table(engine)).filter(v -> v != null).count());
        assertTrue(table(engine).length <= 64, "the UF corpus must fit within the small-cache budget");
    }

    @Test
    void growthPreservesAClusterThatWrapsAroundTheTable() throws Exception {
        final var engine = mapping().newEngine();
        final var cached = new ArrayList<String>();
        // Selected from the real cache fingerprints; all start at slot 15 in a 16-slot table.
        for (String value : List.of("AK", "AL", "BA", "BZ", "CW", "DV", "DW", "ED")) {
            cached.add(extract(engine, value));
        }
        assertEquals(16, table(engine).length);
        final long[] hashes = (long[]) field(engine, "canonHash");
        for (int slot = 0; slot < table(engine).length; slot++) {
            if (table(engine)[slot] != null) assertEquals(15, hashes[slot] & 15);
        }
        assertNotNull(table(engine)[15]);
        assertNotNull(table(engine)[0]);
        assertProbeBound(engine);
        extract(engine, "different");
        assertTrue(table(engine).length > 16);
        for (String value : cached) assertSame(value, extract(engine, value));
        assertProbeBound(engine);
    }

    @Test
    void allStateCodesRemainCanonicalAcrossDocuments() throws Exception {
        final var engine = mapping().newEngine();
        final var values = new ArrayList<String>();
        for (String code : STATE_CODES) {
            values.add(extract(engine, code));
            for (String cached : values) assertSame(cached, extract(engine, cached));
            assertProbeBound(engine);
        }
    }

    @Test
    void collidingStateCodesRemainCanonicalAfterAnotherInsertion() throws Exception {
        final var engine = mapping().newEngine();
        final var cached = new ArrayList<String>();
        // Each value shares the same low four hash bits. The adaptive-cache
        // experiment also used this fixture to exercise a wrapping cluster.
        for (int i = 0; i < 8; i++) {
            final String value = "u" + (char) ('A' + i);
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            assertEquals(15, Swar.hash(bytes, 0, bytes.length) & 15);
            cached.add(extract(engine, value));
        }
        extract(engine, "different");
        for (String value : cached) assertSame(value, extract(engine, value));
        assertProbeBound(engine);
    }

    @Test
    void completeHashCollisionsFallbackWithoutReplacingCachedValues() throws Exception {
        final var engine = mapping().newEngine();
        final var cached = new ArrayList<String>();
        Long hash = null;
        for (int i = 0; i < 32; i++) {
            final String value = "abcdefghijklmnop" + String.format("%08x", i);
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            final long current = Swar.hash(bytes, 0, bytes.length);
            if (hash == null) hash = current;
            assertEquals(hash.longValue(), current, "fixture must collide on the complete hash");
            final String result = extract(engine, value);
            assertEquals(value, result);
            if (i < 8) cached.add(result);
            else assertNotSame(result, extract(engine, value), "exhausted window uses fallback");
        }
        for (String value : cached) assertSame(value, extract(engine, value));
        assertEquals(8, Arrays.stream(table(engine)).filter(v -> v != null).count());
        assertProbeBound(engine);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 23, 91, 20260918})
    void highCardinalityPreservesCachedReferencesAndTheProbeLimit(final int seed) throws Exception {
        final var engine = mapping().newEngine();
        final var random = new Random(seed);
        int previousCapacity = 0;
        String[] previous = new String[0];
        for (int i = 0; i < 4096; i++) {
            final String value = Long.toUnsignedString(random.nextLong(), 36)
                    + Long.toUnsignedString(random.nextLong(), 36);
            assertEquals(value, extract(engine, value));
            final String[] current = table(engine);
            if (current.length != previousCapacity) {
                for (String cached : previous) if (cached != null) {
                    assertSame(cached, extract(engine, cached), "growth lost a cached reference");
                }
                previousCapacity = current.length;
                assertProbeBound(engine);
            }
            previous = current.clone();
        }
        assertEquals(1024, table(engine).length);
        for (String cached : table(engine).clone()) if (cached != null) {
            assertSame(cached, extract(engine, cached), "cache overflow must not evict hits");
        }
        assertProbeBound(engine);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void decodedTextAndAttributesShareValuesAcrossRefills(final int seed) throws Exception {
        final var engine = new XmlMappingEngine<>(mapping(), 16);
        final String expected = "Aé日😀";
        final String first = extract(engine, expected);
        final byte[] xml = ("<r code='A&#233;日😀'>" + " ".repeat(513)
                + "<v><![CDATA[Aé日😀]]></v><v> Aé日😀 </v></r>").getBytes(StandardCharsets.UTF_8);
        final var result = engine.extract(new XmlMappingTest.Drip(xml, seed));
        assertEquals(List.of(expected, expected, expected), result);
        for (String value : result) assertSame(first, value);
        assertNull(field(engine, "b"));
        assertNull(field(engine, "src"));
    }

    @Test
    void maximumLengthIsMeasuredInUtf8Bytes() {
        final var engine = mapping().newEngine();
        final String boundary = "é".repeat(32);
        assertSame(extract(engine, boundary), extract(engine, boundary));
        final String oversized = boundary + "a";
        final String first = extract(engine, oversized);
        final String second = extract(engine, oversized);
        assertEquals(oversized, first);
        assertEquals(first, second);
        assertNotSame(first, second);
    }

    @Test
    void sourceMutationAndFailedExtractionsDoNotPoisonTheCache() throws Exception {
        final var engine = mapping().newEngine();
        final byte[] source = "<r><v>SP</v></r>".getBytes(StandardCharsets.UTF_8);
        final String first = engine.extract(source).get(0);
        Arrays.fill(source, (byte) 'x');
        assertThrows(XmlException.class, () -> engine.extract("<r><v>SC</v><oops></r>"));
        assertSame(first, extract(engine, "SP"));
        assertEquals("SC", extract(engine, "SC"));
        assertNull(field(engine, "b"));
        assertNull(field(field(engine, "val"), "a"));
        for (Object draft : (Object[]) field(engine, "drafts")) assertNull(draft);
    }

    private static String extract(final XmlMappingEngine<List<String>> engine, final String value) {
        return engine.extract("<r><v>" + value + "</v></r>").get(0);
    }

    private static String[] table(final Object engine) throws Exception {
        return (String[]) field(engine, "canonVal");
    }

    private static void assertProbeBound(final Object engine) throws Exception {
        final String[] values = table(engine);
        final long[] hashes = (long[]) field(engine, "canonHash");
        final byte[][] bytes = (byte[][]) field(engine, "canonBytes");
        assertTrue(values.length <= 1024);
        assertEquals(values.length, hashes.length);
        assertEquals(values.length, bytes.length);
        final int mask = values.length - 1;
        for (int slot = 0; slot < values.length; slot++) if (values[slot] != null) {
            final int home = (int) hashes[slot] & mask;
            assertTrue(((slot - home) & mask) < 8, "entry beyond the lookup window");
            for (int i = home; i != slot; i = (i + 1) & mask) {
                assertNotNull(values[i], "a hole would hide the cached entry");
            }
            assertEquals(values[slot], new String(bytes[slot], StandardCharsets.UTF_8));
        }
    }

    private static Object field(final Object target, final String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // The scanner owns the source references.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
