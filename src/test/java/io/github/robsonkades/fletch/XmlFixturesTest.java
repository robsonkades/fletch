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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage driven by XML fixtures under {@code src/test/resources/fixtures}.
 *
 * <p>Each test loads a real {@code .xml} file and, wherever it matters, reads the
 * fields in a <em>different</em> order than they appear in the document — the point
 * being to exercise the order-tolerant cursor across a broad spread of shapes:
 * reordered leaves and nested blocks, collections, {@code xsd:choice}, all supported
 * types, CDATA / mixed content / entities / whitespace, namespaces, deep and wide
 * structures, encodings, and malformed inputs.
 */
class XmlFixturesTest {

    enum Status { ACTIVE, INACTIVE }

    record Address(String city, String zip) {}
    record Employee(String firstName, String lastName) {}
    record Customer(String name, Address address) {}
    record Item(String sku, Integer qty) {}
    record Invoice(Long number, boolean urgent, Customer customer, List<Item> items, BigDecimal total) {}

    // Extractors deliberately request fields in a fixed order; fixtures vary the
    // document order to prove the result is independent of it.
    static final XmlExtractor<Address> ADDRESS = a -> new Address(
            a.value("city", String.class),
            a.value("zip", String.class));

    static final XmlExtractor<Employee> EMPLOYEE = e -> new Employee(
            e.value("firstName", String.class),
            e.value("lastName", String.class));

    static final XmlExtractor<Item> ITEM = i -> new Item(
            i.value("sku", String.class),
            i.value("qty", Integer.class));

    static final XmlExtractor<Customer> CUSTOMER = c -> new Customer(
            c.value("name", String.class),
            c.child("address", ADDRESS));

    private static <T> T extract(final String fixture, final XmlExtractor<T> extractor) {
        try (InputStream in = XmlFixturesTest.class.getResourceAsStream(fixture)) {
            assertNotNull(in, () -> "fixture not found on classpath: " + fixture);
            return Xml.extract(in, extractor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("Arbitrary order")
    class ArbitraryOrder {

        @Test
        void reorderedLeavesMatchNaturalOrder() {
            Address expected = new Address("Curitiba", "80000-000");
            assertEquals(expected, extract("/fixtures/order/address-natural.xml",
                    doc -> doc.child("address", ADDRESS)));
            assertEquals(expected, extract("/fixtures/order/address-reordered.xml",
                    doc -> doc.child("address", ADDRESS)));
        }

        @Test
        void fullyReversedDocumentReadsAscending() {
            int[] values = extract("/fixtures/order/reverse-full.xml", doc -> doc.child("person", p -> new int[]{
                    p.value("a", Integer.class),
                    p.value("b", Integer.class),
                    p.value("c", Integer.class),
                    p.value("d", Integer.class),
            }));

            assertEquals("[1, 2, 3, 4]", java.util.Arrays.toString(values));
        }

        @Test
        void nestedBlockReadBeforeAnEarlierSibling() {
            Integer first = extract("/fixtures/order/nested-before-sibling.xml",
                    doc -> doc.child("r", r -> {
                        Integer x = r.child("second", s -> s.value("x", Integer.class));
                        assertEquals(9, x);
                        return r.value("first", Integer.class); // earlier sibling, from buffer
                    }));

            assertEquals(1, first);
        }

        @Test
        void deepReorderingAcrossLevelsAndBufferedSubtrees() {
            List<Employee> employees = extract("/fixtures/order/deep-reordered.xml",
                    doc -> doc.child("company", co -> co.child("department", dep -> {
                        // <name> appears AFTER <employees>; reading it first buffers the whole
                        // <employees> subtree, which is then re-read from the buffer.
                        assertEquals("Engineering", dep.value("name", String.class));
                        return dep.child("employees", es -> es.children("employee", EMPLOYEE));
                    })));

            assertEquals(List.of(new Employee("Ana", "Souza"), new Employee("Bruno", "Lima")), employees);
        }

        @Test
        void repeatedNameReturnsSuccessiveOccurrences() {
            List<String> tags = extract("/fixtures/order/duplicates.xml",
                    doc -> doc.child("tags", t -> t.children("tag", XmlCursor::name)));
            assertEquals(3, tags.size());

            String[] sequential = extract("/fixtures/order/duplicates.xml",
                    doc -> doc.child("tags", t -> new String[]{
                            t.value("tag", String.class),
                            t.value("tag", String.class),
                            t.value("tag", String.class),
                    }));
            assertEquals("[a, b, c]", java.util.Arrays.toString(sequential));
        }

        @Test
        void missInTheMiddleDoesNotBlockLaterReads() {
            String result = extract("/fixtures/order/optional-missing-middle.xml",
                    doc -> doc.child("r", r -> {
                        assertEquals(1, r.value("a", Integer.class));
                        assertNull(r.value("b", Integer.class)); // absent middle element
                        return r.value("c", Integer.class) + "";  // still readable
                    }));

            assertEquals("3", result);
        }

        @Test
        void firstOfMatchesWhicheverAlternativeIsPresent() {
            String cnpj = extract("/fixtures/order/choice-cnpj-first.xml",
                    doc -> doc.child("dest", d -> d.firstOf(String.class, "CPF", "CNPJ")));
            assertEquals("111", cnpj);

            // <CPF> follows <name>; firstOf skips (and buffers) <name>, then <name> is
            // still readable from the buffer afterwards.
            String name = extract("/fixtures/order/choice-cpf-after.xml",
                    doc -> doc.child("dest", d -> {
                        assertEquals("222", d.firstOf(String.class, "CPF", "CNPJ"));
                        return d.value("name", String.class);
                    }));
            assertEquals("ACME", name);
        }
    }

    @Nested
    @DisplayName("Types")
    class Types {

        @Test
        void allSupportedTypesReadOutOfOrder() {
            extract("/fixtures/types/all-types.xml", doc -> doc.child("data", d -> {
                // request order intentionally scrambled versus the document
                assertEquals(Status.ACTIVE, d.value("e", Status.class));
                assertEquals("hello", d.value("s", String.class));
                assertEquals(Instant.parse("2026-01-15T10:30:00Z"), d.value("ts", Instant.class));
                assertEquals(42, d.value("i", Integer.class));
                assertEquals(9_999_999_999L, d.value("l", Long.class));
                assertEquals(new BigDecimal("10.50"), d.value("bd", BigDecimal.class));
                assertEquals(1.5, d.value("d", Double.class));
                assertEquals(Boolean.TRUE, d.value("b", Boolean.class));
                return null;
            }));
        }

        @Test
        void booleanAcceptsTrueFalseOneZeroCaseInsensitively() {
            extract("/fixtures/types/booleans.xml", doc -> doc.child("flags", f -> {
                assertEquals(Boolean.TRUE, f.value("t1", Boolean.class));
                assertEquals(Boolean.TRUE, f.value("t2", Boolean.class));
                assertEquals(Boolean.TRUE, f.value("t3", Boolean.class));
                assertEquals(Boolean.FALSE, f.value("f1", Boolean.class));
                assertEquals(Boolean.FALSE, f.value("f2", Boolean.class));
                assertEquals(Boolean.FALSE, f.value("f3", Boolean.class));
                return null;
            }));
        }

        @Test
        void negativeAndDecimalNumbers() {
            extract("/fixtures/types/negatives-and-decimals.xml", doc -> doc.child("numbers", n -> {
                assertEquals(-42, n.value("negInt", Integer.class));
                assertEquals(-9_999_999_999L, n.value("negLong", Long.class));
                assertEquals(-1.5, n.value("negDouble", Double.class));
                assertEquals(new BigDecimal("-12345.6789"), n.value("bigDecimal", BigDecimal.class));
                assertEquals(0, n.value("zero", Integer.class));
                return null;
            }));
        }
    }

    @Nested
    @DisplayName("Text content")
    class TextContent {

        @Test
        void cdataIsReadLiterally() {
            assertEquals("5 < 6 & 7 > 3", extract("/fixtures/text/cdata.xml",
                    doc -> doc.child("r", r -> r.value("expr", String.class))));
        }

        @Test
        void mixedContentConcatenatesNestedText() {
            assertEquals("hello world!", extract("/fixtures/text/mixed-content.xml",
                    doc -> doc.value("message", String.class)));
        }

        @Test
        void surroundingWhitespaceTrimmedAndBlankIsNull() {
            extract("/fixtures/text/whitespace.xml", doc -> doc.child("r", r -> {
                assertEquals("hi", r.value("padded", String.class));
                assertNull(r.value("blank", String.class));
                return null;
            }));
        }

        @Test
        void entitiesAreDecoded() {
            assertEquals("a < b && c > d", extract("/fixtures/text/entities.xml",
                    doc -> doc.child("r", r -> r.value("expr", String.class))));
        }

        @Test
        void selfClosingAndEmptyElementsAreNull() {
            extract("/fixtures/text/empty-elements.xml", doc -> doc.child("r", r -> {
                assertNull(r.value("selfClosing", String.class));
                assertNull(r.value("empty", String.class));
                assertEquals("x", r.value("full", String.class));
                return null;
            }));
        }

        @Test
        void unicodeContentPreserved() {
            assertEquals("São Paulo — Ação Ñoño 日本語 ✓", extract("/fixtures/text/unicode.xml",
                    doc -> doc.child("r", r -> r.value("name", String.class))));
        }
    }

    @Nested
    @DisplayName("Structure")
    class Structure {

        @Test
        void deeplyNestedSingleChild() {
            String v = extract("/fixtures/structure/deep-nesting.xml",
                    doc -> doc.child("l1", a -> a.child("l2", b -> b.child("l3", c -> c.child("l4",
                            d -> d.child("l5", e -> e.value("v", String.class)))))));
            assertEquals("deep", v);
        }

        @Test
        void wideSiblingsReadFromTheEndBackwards() {
            extract("/fixtures/structure/wide-siblings.xml", doc -> doc.child("list", l -> {
                assertEquals(20, l.value("n20", Integer.class)); // buffers n1..n19
                assertEquals(1, l.value("n1", Integer.class));   // from buffer
                assertEquals(10, l.value("n10", Integer.class)); // from buffer
                return null;
            }));
        }

        @Test
        void defaultNamespaceMatchesByPlainName() {
            extract("/fixtures/structure/namespace-default.xml", doc -> doc.child("order", o -> {
                assertEquals(7, o.value("id", Integer.class));
                assertEquals(new BigDecimal("99.90"), o.value("total", BigDecimal.class));
                return null;
            }));
        }

        @Test
        void prefixedNamespaceMatchesByFullTagName() {
            Integer id = extract("/fixtures/structure/namespace-prefixed.xml",
                    doc -> doc.child("ns:order", o -> o.value("ns:id", Integer.class)));
            assertEquals(7, id);
        }

        @Test
        void attributesReadableInAnyOrderIncludingAfterChildNavigation() {
            extract("/fixtures/structure/attributes.xml", doc -> doc.child("order", o -> {
                assertEquals("X-1", o.child("item", i -> i.attribute("sku", String.class)));
                // order's own attributes remain readable after navigating into <item>
                assertEquals(7, o.attribute("id", Integer.class));
                assertEquals(new BigDecimal("99.90"), o.attribute("total", BigDecimal.class));
                assertEquals(Boolean.TRUE, o.attribute("urgent", Boolean.class));
                assertEquals("ABC", o.attribute("ref", String.class));
                return null;
            }));
        }
    }

    @Nested
    @DisplayName("Encoding")
    class Encoding {

        @Test
        void latin1EncodingAutoDetectedFromDeclaration() {
            String name = extract("/fixtures/encoding/iso-8859-1.xml",
                    doc -> doc.child("city", c -> c.value("name", String.class)));
            assertEquals("São Paulo", name);
        }
    }

    @Nested
    @DisplayName("Malformed input")
    class Errors {

        @Test
        void mismatchedTagsThrow() {
            assertThrows(XmlException.class, () -> extract("/fixtures/errors/mismatched-tags.xml",
                    doc -> doc.child("book", b -> b.value("title", String.class))));
        }

        @Test
        void truncatedDocumentThrows() {
            assertThrows(XmlException.class, () -> extract("/fixtures/errors/truncated.xml",
                    doc -> doc.child("book", b -> b.value("title", String.class))));
        }

        @Test
        void undeclaredEntityThrows() {
            assertThrows(XmlException.class, () -> extract("/fixtures/errors/undeclared-entity.xml",
                    doc -> doc.child("a", a -> a.value("b", String.class))));
        }

        @Test
        void doctypeWithExternalEntityIsRejected() {
            assertThrows(XmlException.class, () -> extract("/fixtures/errors/xxe-doctype.xml",
                    doc -> doc.child("r", r -> r.value("a", String.class))));
        }
    }

    @Nested
    @DisplayName("Realistic document")
    class Realistic {

        @Test
        void invoiceWithReorderedSectionsAndNestedBuffers() {
            Invoice invoice = extract("/fixtures/realistic/invoice.xml", doc -> doc.child("invoice", inv -> {
                Long number = inv.attribute("number", Long.class);
                Boolean urgent = inv.attribute("urgent", Boolean.class);
                Customer customer = inv.child("customer", CUSTOMER);          // appears after <items>
                List<Item> items = inv.child("items", it -> it.children("item", ITEM)); // from buffer
                BigDecimal total = inv.value("total", BigDecimal.class);      // after <customer>, from stream
                return new Invoice(number, Boolean.TRUE.equals(urgent), customer, items, total);
            }));

            Invoice expected = new Invoice(
                    1042L,
                    true,
                    new Customer("Ana Souza", new Address("Curitiba", "80000-000")),
                    List.of(new Item("A-100", 2), new Item("B-200", 1)),
                    new BigDecimal("150.00"));
            assertEquals(expected, invoice);
        }
    }
}
