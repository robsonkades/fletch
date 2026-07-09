/*
 * Copyright 2026 Robson Kades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link XmlCursor} navigation semantics: typed values, nesting,
 * collections, scope exhaustion, skipping and order-tolerant reads.
 */
class XmlCursorTest {

    enum Status { ACTIVE, INACTIVE }

    @Nested
    @DisplayName("value() — typed leaf reads")
    class Values {

        @Test
        void readsAllSupportedTypesInDocumentOrder() {
            String xml = "<r>"
                    + "<s>hello</s>"
                    + "<i>42</i>"
                    + "<l>9999999999</l>"
                    + "<d>1.5</d>"
                    + "<bd>10.50</bd>"
                    + "<b>true</b>"
                    + "<ts>2026-01-15T10:30:00Z</ts>"
                    + "<e>ACTIVE</e>"
                    + "</r>";

            Xml.extract(xml, doc -> doc.child("r", r -> {
                assertEquals("hello", r.value("s", String.class));
                assertEquals(42, r.value("i", Integer.class));
                assertEquals(9_999_999_999L, r.value("l", Long.class));
                assertEquals(1.5, r.value("d", Double.class));
                assertEquals(new BigDecimal("10.50"), r.value("bd", BigDecimal.class));
                assertEquals(Boolean.TRUE, r.value("b", Boolean.class));
                assertEquals(Instant.parse("2026-01-15T10:30:00Z"), r.value("ts", Instant.class));
                assertEquals(Status.ACTIVE, r.value("e", Status.class));
                return null;
            }));
        }

        @Test
        void absentElementYieldsNull() {
            String result = Xml.extract("<r><a>1</a></r>",
                    doc -> doc.child("r", r -> r.value("missing", String.class)));

            assertNull(result);
        }

        @Test
        void selfClosingElementYieldsNull() {
            assertNull(Xml.extract("<r><a/></r>",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }

        @Test
        void emptyElementYieldsNull() {
            assertNull(Xml.extract("<r><a></a></r>",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }

        @Test
        void whitespaceOnlyElementYieldsNull() {
            assertNull(Xml.extract("<r><a>   </a></r>",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }

        @Test
        void surroundingWhitespaceIsTrimmed() {
            assertEquals("hi", Xml.extract("<r><a>  hi  </a></r>",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }

        @Test
        void readsCdataContent() {
            assertEquals("5 < 6 & 7", Xml.extract("<r><a><![CDATA[5 < 6 & 7]]></a></r>",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }

        @Test
        void assemblesTextSplitAcrossParserChunks() {
            String bigText = "x".repeat(100_000);
            String xml = "<r><a>" + bigText + "</a></r>";

            String result = Xml.extract(xml, doc -> doc.child("r", r -> r.value("a", String.class)));

            assertEquals(bigText, result);
        }

        @Test
        void mixedContentConcatenatesNestedText() {
            assertEquals("hello world!", Xml.extract("<a>hello <b>world</b>!</a>",
                    doc -> doc.value("a", String.class)));
        }
    }

    @Nested
    @DisplayName("child() — nested extraction")
    class Child {

        record Address(String city, String zip) {}
        record Customer(String name, Address address) {}

        static final XmlExtractor<Address> ADDRESS = a -> new Address(
                a.value("city", String.class),
                a.value("zip", String.class));

        @Test
        void composesNestedExtractors() {
            String xml = "<customer><name>Ana</name>"
                    + "<address><city>Curitiba</city><zip>80000-000</zip></address>"
                    + "</customer>";

            Customer customer = Xml.extract(xml, doc -> doc.child("customer", c -> new Customer(
                    c.value("name", String.class),
                    c.child("address", ADDRESS))));

            assertEquals(new Customer("Ana", new Address("Curitiba", "80000-000")), customer);
        }

        @Test
        void drainsContentTheExtractorLeftUnread() {
            String xml = "<r><big><x>1</x><y>2</y><z>3</z></big><after>ok</after></r>";

            String after = Xml.extract(xml, doc -> doc.child("r", r -> {
                Integer x = r.child("big", big -> big.value("x", Integer.class));
                assertEquals(1, x);
                // big's unread <y> and <z> must not leak into r's scope
                return r.value("after", String.class);
            }));

            assertEquals("ok", after);
        }

        @Test
        void skipsNonMatchingSiblingsBeforeTheTarget() {
            String xml = "<r><noise><deep>x</deep></noise><target>hit</target></r>";

            assertEquals("hit", Xml.extract(xml,
                    doc -> doc.child("r", r -> r.value("target", String.class))));
        }
    }

    @Nested
    @DisplayName("children() — collections")
    class Children {

        record Item(String sku, Integer qty) {}

        static final XmlExtractor<Item> ITEM = i -> new Item(
                i.value("sku", String.class),
                i.value("qty", Integer.class));

        @Test
        void collectsAllMatchesInDocumentOrder() {
            String xml = "<cart>"
                    + "<item><sku>A</sku><qty>1</qty></item>"
                    + "<item><sku>B</sku><qty>2</qty></item>"
                    + "</cart>";

            List<Item> items = Xml.extract(xml, doc -> doc.child("cart", c -> c.children("item", ITEM)));

            assertEquals(List.of(new Item("A", 1), new Item("B", 2)), items);
        }

        @Test
        void skipsInterleavedNonMatchingSiblings() {
            String xml = "<cart>"
                    + "<item><sku>A</sku><qty>1</qty></item>"
                    + "<promo>ignored</promo>"
                    + "<item><sku>B</sku><qty>2</qty></item>"
                    + "</cart>";

            List<Item> items = Xml.extract(xml, doc -> doc.child("cart", c -> c.children("item", ITEM)));

            assertEquals(List.of(new Item("A", 1), new Item("B", 2)), items);
        }

        @Test
        void noMatchesYieldsEmptyMutableList() {
            List<Item> items = Xml.extract("<cart><promo/></cart>",
                    doc -> doc.child("cart", c -> c.children("item", ITEM)));

            assertTrue(items.isEmpty());
            items.add(new Item("mutable", 0)); // must not throw
        }
    }

    @Nested
    @DisplayName("firstOf() — xsd:choice alternatives")
    class FirstOf {

        @Test
        void matchesWhicheverAlternativeIsPresent() {
            String cnpj = Xml.extract("<dest><CNPJ>123</CNPJ><name>ACME</name></dest>",
                    doc -> doc.child("dest", d -> d.firstOf(String.class, "CPF", "CNPJ")));

            assertEquals("123", cnpj);
        }

        @Test
        void matchesByDocumentOrderNotArgumentOrder() {
            String result = Xml.extract("<dest><CPF>1</CPF><CNPJ>2</CNPJ></dest>",
                    doc -> doc.child("dest", d -> d.firstOf(String.class, "CNPJ", "CPF")));

            assertEquals("1", result, "the first matching element in the document wins");
        }

        @Test
        void noAlternativePresentYieldsNull() {
            assertNull(Xml.extract("<dest><other>x</other></dest>",
                    doc -> doc.child("dest", d -> d.firstOf(String.class, "CPF", "CNPJ"))));
        }

        @Test
        void scanContinuesWithLaterSiblingsAfterAMatch() {
            String name = Xml.extract("<dest><CPF>1</CPF><name>Ana</name></dest>",
                    doc -> doc.child("dest", d -> {
                        assertEquals("1", d.firstOf(String.class, "CPF", "CNPJ"));
                        return d.value("name", String.class);
                    }));

            assertEquals("Ana", name);
        }
    }

    @Nested
    @DisplayName("attribute()")
    class Attributes {

        @Test
        void readsTypedAttributesAtStartElement() {
            String xml = "<order id=\"7\" total=\"99.90\" urgent=\"1\"><item/></order>";

            Xml.extract(xml, doc -> doc.child("order", o -> {
                assertEquals(7, o.attribute("id", Integer.class));
                assertEquals(new BigDecimal("99.90"), o.attribute("total", BigDecimal.class));
                assertEquals(Boolean.TRUE, o.attribute("urgent", Boolean.class));
                return null;
            }));
        }

        @Test
        void absentAttributeYieldsNull() {
            assertNull(Xml.extract("<order id=\"7\"/>",
                    doc -> doc.child("order", o -> o.attribute("missing", String.class))));
        }

        @Test
        void emptyAttributeYieldsNull() {
            assertNull(Xml.extract("<order id=\"\"/>",
                    doc -> doc.child("order", o -> o.attribute("id", String.class))));
        }

        @Test
        void attributeReadableAfterChildNavigation() {
            String xml = "<order id=\"7\"><item>x</item></order>";

            Integer id = Xml.extract(xml, doc -> doc.child("order", o -> {
                o.value("item", String.class); // navigates past the start tag
                return o.attribute("id", Integer.class); // attributes are snapshotted on entry
            }));

            assertEquals(7, id);
        }
    }

    @Nested
    @DisplayName("name() and skip()")
    class NameAndSkip {

        @Test
        void nameReturnsTheCurrentElementTag() {
            assertEquals("order", Xml.extract("<order/>",
                    doc -> doc.child("order", XmlCursor::name)));
        }

        @Test
        void skipDiscardsAnElementAndItsSubtree() {
            String xml = "<r><noise><deep><deeper>x</deeper></deep></noise><b>2</b></r>";

            Integer b = Xml.extract(xml, doc -> doc.child("r", r -> {
                r.child("noise", noise -> {
                    noise.skip();
                    return null;
                });
                return r.value("b", Integer.class);
            }));

            assertEquals(2, b);
        }

        @Test
        void afterSkipTheScopeReportsAbsence() {
            Integer b = Xml.extract("<r><a>1</a><b>2</b></r>", doc -> doc.child("r", r -> {
                r.skip(); // consumes all of <r>
                return r.value("b", Integer.class);
            }));

            assertNull(b);
        }
    }

    @Nested
    @DisplayName("Scope exhaustion")
    class ScopeExhaustion {

        @Test
        void missOnOptionalTrailingElementYieldsNullForItAndEverythingAfter() {
            Xml.extract("<r><a>1</a></r>", doc -> doc.child("r", r -> {
                assertEquals(1, r.value("a", Integer.class));
                assertNull(r.value("optional", String.class));
                assertNull(r.value("alsoOptional", String.class));
                return null;
            }));
        }

        @Test
        void childrenAfterAnExhaustedScopeYieldsEmptyList() {
            Xml.extract("<r><a>1</a></r>", doc -> doc.child("r", r -> {
                assertNull(r.value("missing", String.class)); // exhausts <r>
                assertTrue(r.children("b", x -> x).isEmpty());
                return null;
            }));
        }
    }

    @Nested
    @DisplayName("Arbitrary order")
    class ArbitraryOrder {

        record Address(String city, String zip) {}

        static final XmlExtractor<Address> ADDRESS = a -> new Address(
                a.value("city", String.class),
                a.value("zip", String.class));

        @Test
        void leafValuesReadRegardlessOfDocumentOrder() {
            String reordered = "<address><zip>80000-000</zip><city>Curitiba</city></address>";
            String natural = "<address><city>Curitiba</city><zip>80000-000</zip></address>";

            Address expected = new Address("Curitiba", "80000-000");
            assertEquals(expected, Xml.extract(reordered, doc -> doc.child("address", ADDRESS)));
            assertEquals(expected, Xml.extract(natural, doc -> doc.child("address", ADDRESS)));
        }

        @Test
        void readsAnElementRequestedAgainstDocumentOrder() {
            Integer a = Xml.extract("<r><a>1</a><b>2</b></r>", doc -> doc.child("r", r -> {
                assertEquals(2, r.value("b", Integer.class)); // scan buffers <a>
                return r.value("a", Integer.class);           // served from the buffer
            }));

            assertEquals(1, a);
        }

        @Test
        void readsANestedChildBeforeAnEarlierSibling() {
            String xml = "<r><first>1</first><second><x>9</x></second></r>";

            Integer first = Xml.extract(xml, doc -> doc.child("r", r -> {
                Integer x = r.child("second", s -> s.value("x", Integer.class));
                assertEquals(9, x);
                return r.value("first", Integer.class); // earlier sibling, from buffer
            }));

            assertEquals(1, first);
        }

        @Test
        void firstOfMatchesAnAlternativeRequestedAfterALaterSibling() {
            String xml = "<dest><CPF>123</CPF><name>ACME</name></dest>";

            String cpf = Xml.extract(xml, doc -> doc.child("dest", d -> {
                assertEquals("ACME", d.value("name", String.class)); // buffers <CPF>
                return d.firstOf(String.class, "CPF", "CNPJ");        // from buffer
            }));

            assertEquals("123", cpf);
        }

        @Test
        void readsElementsInDocumentOrderWithoutBuffering() {
            Xml.extract("<r><a>1</a><b>2</b></r>", doc -> doc.child("r", r -> {
                assertEquals(1, r.value("a", Integer.class));
                assertEquals(2, r.value("b", Integer.class));
                return null;
            }));
        }
    }
}
