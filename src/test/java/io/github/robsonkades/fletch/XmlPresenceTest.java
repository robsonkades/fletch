/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XmlPresenceTest {
    enum Input {
        STRING, BYTES, STREAM, SHORT_READS, UTF16, LATIN1;

        <T> T read(final String xml, final XmlExtractor<T> extractor) {
            return read(xml, XmlLimits.defaults(), extractor);
        }

        <T> T read(final String xml, final XmlLimits limits, final XmlExtractor<T> extractor) {
            final byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            return switch (this) {
                case STRING -> Xml.extract(xml, limits, extractor);
                case BYTES -> Xml.extract(bytes, limits, extractor);
                case STREAM -> Xml.extract(new ByteArrayInputStream(bytes), limits, extractor);
                case SHORT_READS -> Xml.extract(new ByteArrayInputStream(bytes) {
                    @Override public synchronized int read(final byte[] b, final int off, final int len) {
                        return super.read(b, off, Math.min(len, 1));
                    }
                }, limits, extractor);
                case UTF16 -> Xml.extract(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16)),
                        limits, extractor);
                case LATIN1 -> Xml.extract(("<?xml version='1.0' encoding='ISO-8859-1'?>" + xml)
                        .getBytes(StandardCharsets.ISO_8859_1), limits, extractor);
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void rootAndRepeatedProbesPreserveTheNextRead(final Input input) {
        final String result = input.read("<r a='attr'><v>Café &amp; <![CDATA[ok]]></v></r>", doc -> {
            assertTrue(doc.exists("r"));
            assertTrue(doc.exists("r"));
            final String value = doc.child("r", r -> {
                assertTrue(r.exists("v"));
                assertTrue(r.exists("v"));
                assertEquals("attr", r.attribute("a", String.class));
                final String text = r.value("v", String.class);
                assertFalse(r.exists("v"));
                return text;
            });
            assertFalse(doc.exists("r"));
            return value;
        });
        assertEquals("Café & ok", result);
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void missesAndOutOfOrderProbesKeepAllSiblingsAvailable(final Input input) {
        input.read("<r><item id='1'/><first>A</first><item id='2'/><last>Z</last><item id='3'/></r>",
                doc -> doc.child("r", r -> {
                    assertTrue(r.exists("last"));
                    assertTrue(r.exists("item"));
                    assertFalse(r.exists("absent"));
                    assertTrue(r.exists("last"));
                    assertEquals("A", r.firstOf(String.class, "last", "first"));
                    assertFalse(r.exists("first"));
                    assertEquals(List.of(1, 2, 3),
                            r.children("item", item -> item.attribute("id", Integer.class)));
                    assertFalse(r.exists("item"));
                    assertEquals("Z", r.value("last", String.class));
                    assertFalse(r.exists("last"));
                    return null;
                }));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void emptyBlankAndRepeatedElementsStillExistUntilConsumed(final Input input) {
        input.read("<r><v/><v>  </v><v>text</v><v><![CDATA[]]></v></r>", doc -> doc.child("r", r -> {
            assertTrue(r.exists("v"));
            assertNull(r.value("v", String.class));
            assertTrue(r.exists("v"));
            assertNull(r.value("v", String.class));
            assertTrue(r.exists("v"));
            assertEquals("text", r.value("v", String.class));
            assertTrue(r.exists("v"));
            assertNull(r.value("v", String.class));
            assertFalse(r.exists("v"));
            return null;
        }));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void probingANestedElementPreservesChildNavigationAndAttributes(final Input input) {
        input.read("<r><box id='7'><v>inside</v><inner><v>deeper</v></inner></box><v>outside</v></r>",
                doc -> doc.child("r", r -> {
                    assertTrue(r.exists("box"));
                    final List<String> nested = r.child("box", box -> {
                        assertEquals(7, box.attribute("id", Integer.class));
                        assertTrue(box.exists("inner"));
                        assertTrue(box.exists("v"));
                        assertEquals("inside", box.value("v", String.class));
                        assertFalse(box.exists("v"));
                        final String value = box.child("inner", inner -> {
                            assertTrue(inner.exists("v"));
                            return inner.value("v", String.class);
                        });
                        return List.of(value);
                    });
                    assertEquals(List.of("deeper"), nested);
                    assertEquals("outside", r.value("v", String.class));
                    return null;
                }));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void existenceOnlyMatchesDirectChildrenByTheirRawNames(final Input input) {
        input.read("<r xmlns:p='urn:test'><box><v>deep</v></box><p:v>qualified</p:v><ação>sim</ação></r>",
                doc -> doc.child("r", r -> {
                    assertFalse(r.exists("v"));
                    assertFalse(r.exists("/r/box"));
                    assertFalse(r.exists("xmlns:p"));
                    assertTrue(r.exists("p:v"));
                    assertEquals("qualified", r.value("p:v", String.class));
                    assertTrue(r.exists("ação"));
                    assertEquals("sim", r.value("ação", String.class));
                    assertTrue(r.exists("box"));
                    final Boolean nestedExists = r.child("box", box -> box.exists("v"));
                    assertTrue(nestedExists);
                    return null;
                }));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void skipDiscardsProbedAndUnprobedOccurrences(final Input input) {
        input.read("<r><a>one</a><b>two</b></r>", doc -> doc.child("r", r -> {
            assertTrue(r.exists("a"));
            r.skip();
            assertFalse(r.exists("a"));
            assertFalse(r.exists("b"));
            assertNull(r.value("a", String.class));
            return null;
        }));
    }

    @ParameterizedTest
    @EnumSource(Input.class)
    void probesAndReplaysCountEachElementOnce(final Input input) {
        final String xml = "<r><a><x>one</x><y/></a><b>two</b><c>three</c></r>";
        final XmlExtractor<List<String>> extractor = doc -> {
            assertTrue(doc.exists("r"));
            return doc.child("r", r -> {
                assertTrue(r.exists("c"));
                assertTrue(r.exists("a"));
                assertTrue(r.exists("b"));
                assertTrue(r.exists("c"));
                final String a = r.child("a", child -> {
                    assertTrue(child.exists("x"));
                    assertTrue(child.exists("y"));
                    return child.value("x", String.class);
                });
                return List.of(a, r.value("b", String.class), r.value("c", String.class));
            });
        };
        assertEquals(List.of("one", "two", "three"),
                input.read(xml, XmlLimits.builder().maxElements(6).maxDepth(3).build(), extractor));
        assertThrows(XmlException.class, () -> input.read(xml,
                XmlLimits.builder().maxElements(5).build(), extractor));
        assertThrows(XmlException.class, () -> input.read(xml,
                XmlLimits.builder().maxDepth(2).build(), extractor));
    }

    @Test
    void aWideLookAheadKeepsPendingSpansInDocumentOrder() {
        final var xml = new StringBuilder("<r>");
        final var expected = new ArrayList<Integer>();
        for (int i = 0; i < 64; i++) {
            xml.append("<item id='").append(i).append("'><v>").append(i).append("</v></item>");
            expected.add(i);
        }
        xml.append("<last/></r>");
        final List<Integer> actual = Xml.extract(xml.toString(), doc -> doc.child("r", r -> {
            assertTrue(r.exists("last"));
            assertFalse(r.exists("missing"));
            assertTrue(r.exists("item"));
            return r.children("item", item -> {
                assertTrue(item.exists("v"));
                return item.value("v", Integer.class);
            });
        }));
        assertEquals(expected, actual);
    }

    @Test
    void existenceDoesNotDecodeTextAndSubsequentReadsStillValidateIt() {
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>&unknown;</v></r>",
                doc -> doc.child("r", r -> {
                    assertTrue(r.exists("v"));
                    return r.value("v", String.class);
                })));
        assertThrows(XmlException.class, () -> Xml.extract("<r><v>long text</v></r>",
                XmlLimits.builder().maxTextBytes(2).build(), doc -> doc.child("r", r -> {
                    assertTrue(r.exists("v"));
                    return r.value("v", String.class);
        })));
    }

    @Test
    void aProbeStopsAtTheMatchedStartTagWithoutTraversingItsSubtree() {
        final boolean exists = Xml.extract("<r><nested><deep/></nested></r>",
                XmlLimits.builder().maxDepth(1).maxElements(1).build(), doc -> doc.exists("r"));
        assertTrue(exists);
    }

    @Test
    void aProbeDoesNotWeakenEndTagValidationOfTheFollowingChildRead() {
        assertThrows(XmlException.class, () -> Xml.extract("<r></wrong>", doc -> {
            assertTrue(doc.exists("r"));
            return doc.child("r", r -> "result");
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<r/><r/>", "<r/><other/>"})
    void readingAProbedRootStillRejectsASecondDocumentElement(final String xml) {
        assertThrows(XmlException.class, () -> Xml.extract(xml, doc -> {
            assertTrue(doc.exists("r"));
            doc.child("r", r -> null);
            return doc.exists("missing");
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<r><v>", "<r><v><x/></r>", "<r><v a=bad/></r>",
            "<r><!DOCTYPE v><v/></r>"})
    void malformedScannedStructureIsNotReportedAsAbsence(final String xml) {
        assertThrows(XmlException.class, () -> Xml.extract(xml, doc -> doc.child("r", r -> r.exists("v"))));
    }

    @Test
    void probesRespectStartTagLimits() {
        assertThrows(XmlException.class, () -> Xml.extract("<r><longname/></r>",
                XmlLimits.builder().maxNameBytes(4).build(), doc -> doc.child("r", r -> r.exists("longname"))));
        assertThrows(XmlException.class, () -> Xml.extract("<r><v a='1'/></r>",
                XmlLimits.builder().maxAttributesPerElement(0).build(), doc -> doc.child("r", r -> r.exists("v"))));
    }

    @Test
    void nullNamesFailBeforeScanning() {
        Xml.extract("<r><v>ok</v></r>", doc -> doc.child("r", r -> {
            assertThrows(NullPointerException.class, () -> r.exists(null));
            assertEquals("ok", r.value("v", String.class));
            return null;
        }));
    }

    @Test
    void anAncestorCannotProbeWhileItsLiveChildIsActive() {
        Xml.extract("<r><box><v>ok</v></box><after>done</after></r>", doc -> doc.child("r", r -> {
            assertEquals("ok", r.child("box", box -> {
                assertThrows(XmlException.class, () -> r.exists("after"));
                return box.value("v", String.class);
            }));
            assertTrue(r.exists("after"));
            assertEquals("done", r.value("after", String.class));
            return null;
        }));
    }

    @Test
    void externalImplementationsFailExplicitlyWithoutConsumingOnUnsupportedProbes() {
        Xml.extract("<r><v>ok</v></r>", doc -> doc.child("r", r -> {
            final XmlCursor external = new LegacyCursor(r);
            assertThrows(UnsupportedOperationException.class, () -> external.exists("v"));
            assertThrows(NullPointerException.class, () -> external.exists(null));
            assertEquals("ok", external.valueWith("v", text -> text, () -> "fallback"));
            assertEquals("fallback", external.attributeWith("missing", text -> text, () -> "fallback"));
            return null;
        }));
    }

    // Deliberately implements only the original abstract interface, without exists.
    private static final class LegacyCursor implements XmlCursor {
        private final XmlCursor delegate;
        LegacyCursor(final XmlCursor delegate) { this.delegate = delegate; }
        @Override public <T> T child(final String name, final XmlExtractor<T> extractor) {
            return delegate.child(name, extractor);
        }
        @Override public <T> List<T> children(final String name, final XmlExtractor<T> extractor) {
            return delegate.children(name, extractor);
        }
        @Override public <T> T value(final String name, final Class<T> type) { return delegate.value(name, type); }
        @Override public <T> T firstOf(final Class<T> type, final String... names) { return delegate.firstOf(type, names); }
        @Override public <T> T attribute(final String name, final Class<T> type) { return delegate.attribute(name, type); }
        @Override public String name() { return delegate.name(); }
        @Override public void skip() { delegate.skip(); }
    }
}
