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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class XmlCursorReplayTest {
    @ParameterizedTest
    @ValueSource(ints = {128, 256, 512})
    void collectsEarlierSiblingsOnceAndInOrderUnderExactLimits(final int count) {
        final StringBuilder xml = new StringBuilder("<r>");
        for (int i = 0; i < count; i++) xml.append("<a id='").append(i).append("'/>");
        final byte[] bytes = xml.append("<b/></r>").toString().getBytes(StandardCharsets.UTF_8);
        final XmlLimits limits = XmlLimits.builder().maxInputBytes(bytes.length)
                .maxElements(count + 2).maxDepth(2).build();
        Xml.extract(bytes, limits, d -> d.child("r", r -> {
            r.child("b", b -> null);
            assertTrue(r.exists("a"));
            assertTrue(r.exists("a"));
            assertEquals(IntStream.range(0, count).boxed().toList(),
                    r.children("a", a -> a.attribute("id", Integer.class)));
            assertFalse(r.exists("a"));
            assertTrue(r.children("a", a -> 0).isEmpty());
            return null;
        }));
        assertThrows(XmlException.class, () -> Xml.extract(bytes,
                XmlLimits.builder().maxElements(count + 1).build(),
                d -> d.child("r", r -> r.child("b", b -> null))));
    }

    @Test
    void removingMiddleHeadAndTailPreservesOrderWhenMoreSiblingsAreBuffered() {
        final String xml = "<r><a>1</a><b>2</b><c>3</c><m/>"
                + "<a>4</a><d>5</d><b>6</b><m/><c>7</c></r>";
        Xml.extract(xml, d -> d.child("r", r -> {
            r.child("m", m -> null);
            assertEquals(2, r.value("b", Integer.class));
            r.child("m", m -> null);
            assertEquals(1, r.firstOf(Integer.class, "c", "a"));
            assertEquals(3, r.firstOf(Integer.class, "b", "c"));
            assertEquals(6, r.value("b", Integer.class));
            assertEquals(4, r.value("a", Integer.class));
            assertEquals(5, r.value("d", Integer.class));
            assertEquals(7, r.value("c", Integer.class));
            assertNull(r.firstOf(Integer.class, "a", "b", "c", "d"));
            return null;
        }));
    }

    @Test
    void collectingOneNameKeepsOtherNamesAndLiveMatchesInDocumentOrder() {
        final String xml = "<r><a id='1'/><x>2</x><a id='3'/><b/>"
                + "<x>4</x><a id='5'/></r>";
        Xml.extract(xml, d -> d.child("r", r -> {
            r.child("b", b -> null);
            assertEquals(List.of(1, 3, 5), r.children("a", a -> a.attribute("id", Integer.class)));
            assertEquals(2, r.value("x", Integer.class));
            assertEquals(4, r.value("x", Integer.class));
            assertNull(r.value("x", Integer.class));
            return null;
        }));
    }

    @Test
    void aFailingCallbackConsumesOnlyItsOwnPendingOccurrence() {
        Xml.extract("<r><a id='1'/><a id='2'/><a id='3'/><b/></r>", d -> d.child("r", r -> {
            r.child("b", b -> null);
            assertThrows(IllegalStateException.class,
                    () -> r.children("a", a -> { throw new IllegalStateException("callback"); }));
            assertEquals(List.of(2, 3), r.children("a", a -> a.attribute("id", Integer.class)));
            return null;
        }));
    }

    @Test
    void replayCallbacksMayConsumeOrDiscardRemainingParentSiblings() {
        final String xml = "<r><a id='1'/><a id='2'/><a id='3'/><b/></r>";
        Xml.extract(xml, d -> d.child("r", r -> {
            r.child("b", b -> null);
            assertEquals(List.of(1, 3), r.children("a", a -> {
                final int id = a.attribute("id", Integer.class);
                if (id == 1) assertEquals(2, r.<Integer>child("a", next -> next.attribute("id", Integer.class)));
                return id;
            }));
            return null;
        }));
        Xml.extract(xml, d -> d.child("r", r -> {
            r.child("b", b -> null);
            assertEquals(List.of(1), r.children("a", a -> {
                r.skip();
                return a.attribute("id", Integer.class);
            }));
            return null;
        }));
    }

    @Test
    void mixedReplayOperationsAgreeWithAnOrderedConsumptionModel() {
        final Random random = new Random(20261108);
        final StringBuilder xml = new StringBuilder("<r>");
        final List<Entry> remaining = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            final String name = String.valueOf((char) ('a' + random.nextInt(4)));
            remaining.add(new Entry(name, i));
            xml.append('<').append(name).append(" id='").append(i).append("'>")
                    .append(i).append("</").append(name).append('>');
        }
        xml.append("<marker/></r>");
        Xml.extract(xml.toString(), d -> d.child("r", r -> {
            r.child("marker", m -> null);
            for (int step = 0; step < 256; step++) {
                final String name = String.valueOf((char) ('a' + random.nextInt(4)));
                final Entry next = remaining.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null);
                assertEquals(next != null, r.exists(name));
                final Integer actual = (step & 1) == 0 ? r.value(name, Integer.class)
                        : r.child(name, a -> a.attribute("id", Integer.class));
                assertEquals(next == null ? null : next.id, actual);
                remaining.remove(next);
            }
            for (String name : List.of("a", "b", "c", "d")) {
                assertEquals(remaining.stream().filter(e -> e.name.equals(name)).map(Entry::id).toList(),
                        r.children(name, a -> a.attribute("id", Integer.class)));
            }
            return null;
        }));
    }

    private record Entry(String name, Integer id) {}
}
