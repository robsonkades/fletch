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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the compiled-plan engine ({@link XmlMapping}/{@link XmlMappingEngine}),
 * driven by the same fixtures as {@link XmlFixturesTest} so both APIs are held
 * to identical extraction semantics. Plans deliberately declare paths in a
 * different order than the documents supply them — declaration order must be
 * irrelevant by construction.
 */
class XmlMappingTest {

    enum Status { ACTIVE, INACTIVE }

    // ------------------------------------------------------------------ NF-e (mirrors XmlFixturesTest)

    record NfeIde(Long number, Instant issuedAt) {}
    record NfeParty(String taxId, String name, String uf) {}
    record NfeItem(Integer number, String code, String description, BigDecimal amount) {}
    record NfeProtocol(String accessKey, Integer status, String reason) {}
    record Nfe(String id, NfeIde ide, NfeParty emit, NfeParty dest,
               List<NfeItem> items, BigDecimal totalAmount, NfeProtocol protocol) {}

    static final class NfeDraft {
        String id;
        Long number;
        Instant issuedAt;
        String emitTax, emitName, emitUf;
        String destTax, destName, destUf;
        final List<NfeItem> items = new ArrayList<>();
        BigDecimal total;
        String accessKey;
        Integer status;
        String reason;

        Nfe toNfe() {
            return new Nfe(id, new NfeIde(number, issuedAt),
                    new NfeParty(emitTax, emitName, emitUf),
                    new NfeParty(destTax, destName, destUf),
                    items, total, new NfeProtocol(accessKey, status, reason));
        }
    }

    static final class ItemDraft {
        Integer n;
        String code;
        String description;
        BigDecimal amount;

        NfeItem toItem() {
            return new NfeItem(n, code, description, amount);
        }
    }

    // The protocol block lives at the END of the document but is declared
    // FIRST: with compiled plans, order tolerance costs nothing.
    static final XmlMapping<Nfe> NFE_PLAN = XmlMapping.builder(NfeDraft::new)
            .text("/nfeProc/protNFe/infProt/chNFe", (d, v) -> d.accessKey = v.asString())
            .text("/nfeProc/protNFe/infProt/cStat", (d, v) -> d.status = v.asInt())
            .text("/nfeProc/protNFe/infProt/xMotivo", (d, v) -> d.reason = v.asString())
            .attr("/nfeProc/NFe/infNFe@Id", (d, v) -> d.id = v.asString())
            .text("/nfeProc/NFe/infNFe/ide/nNF", (d, v) -> d.number = v.asLong())
            .text("/nfeProc/NFe/infNFe/ide/dhEmi", (d, v) -> d.issuedAt = v.asInstant())
            .text("/nfeProc/NFe/infNFe/emit/CNPJ", (d, v) -> d.emitTax = v.asString())
            .text("/nfeProc/NFe/infNFe/emit/xNome", (d, v) -> d.emitName = v.asString())
            .text("/nfeProc/NFe/infNFe/emit/enderEmit/UF", (d, v) -> d.emitUf = v.asString())
            .firstOf((d, v) -> d.destTax = v.asString(),
                    "/nfeProc/NFe/infNFe/dest/CPF",
                    "/nfeProc/NFe/infNFe/dest/CNPJ",
                    "/nfeProc/NFe/infNFe/dest/idEstrangeiro")
            .text("/nfeProc/NFe/infNFe/dest/xNome", (d, v) -> d.destName = v.asString())
            .text("/nfeProc/NFe/infNFe/dest/enderDest/UF", (d, v) -> d.destUf = v.asString())
            .group("/nfeProc/NFe/infNFe/det", ItemDraft::new, (d, i) -> d.items.add(i.toItem()))
                .attr("@nItem", (i, v) -> i.n = v.asInt())
                .text("prod/cProd", (i, v) -> i.code = v.asString())
                .text("prod/xProd", (i, v) -> i.description = v.asString())
                .text("prod/vProd", (i, v) -> i.amount = v.asDecimal())
                .endGroup()
            .text("/nfeProc/NFe/infNFe/total/ICMSTot/vNF", (d, v) -> d.total = v.asDecimal())
            .build(NfeDraft::toNfe);

    static final Nfe EXPECTED_NFE = new Nfe(
            "NFe35240638167943000186550010000274361328488329",
            new NfeIde(27436L, Instant.parse("2024-06-20T14:27:22Z")),
            new NfeParty("38167943000186", "FULL CYCLE LTDA", "SP"),
            new NfeParty("08145454913", "ROBSON KADES", "SC"),
            List.of(new NfeItem(1, "PRD00003",
                    "Material Didatico - MBA Arquitetura Full Cycle", new BigDecimal("263.89"))),
            new BigDecimal("263.89"),
            new NfeProtocol("35240638167943000186550010000274361328488329", 100, "Autorizado o uso da NF-e"));

    private static byte[] fixture(final String path) {
        try (InputStream in = XmlMappingTest.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "fixture not found on classpath: " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Feeds bytes in random 1-7 byte sips — hammers every window-refill path. */
    static final class Drip extends InputStream {
        private final byte[] data;
        private final java.util.Random rnd;
        private int pos;

        Drip(final byte[] data, final long seed) {
            this.data = data;
            this.rnd = new java.util.Random(seed);
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(final byte[] out, final int off, final int len) {
            if (pos >= data.length) return -1;
            final int k = Math.min(len, Math.min(1 + rnd.nextInt(7), data.length - pos));
            System.arraycopy(data, pos, out, off, k);
            pos += k;
            return k;
        }
    }

    /** Runs a one-leaf plan over {@code <v>...</v>} and applies an accessor. */
    @SuppressWarnings("unchecked")
    private static <R> R leafValue(final String xml, final Function<XmlValue, R> accessor) {
        final Object[] box = new Object[1];
        final XmlMapping<Object> plan = XmlMapping.builder(() -> box)
                .text("/v", (d, v) -> d[0] = accessor.apply(v))
                .build(d -> d[0]);
        return (R) plan.extract(xml);
    }

    @Nested
    @DisplayName("NF-e end to end")
    class NfeEndToEnd {

        @Test
        void extractsTheAuthorizedInvoiceFromBytes() {
            assertEquals(EXPECTED_NFE, NFE_PLAN.extract(
                    fixture("/fixtures/invoice/35240638167943000186550010000274361328488329-procNFe.xml")));
        }

        @Test
        void byteStringAndStreamEntriesAgree() throws IOException {
            final byte[] doc = fixture("/fixtures/invoice/35240638167943000186550010000274361328488329-procNFe.xml");
            assertEquals(EXPECTED_NFE, NFE_PLAN.extract(new String(doc, StandardCharsets.UTF_8)));

            final boolean[] closed = {false};
            final InputStream in = new FilterInputStream(new ByteArrayInputStream(doc)) {
                @Override
                public void close() throws IOException {
                    closed[0] = true;
                    super.close();
                }
            };
            assertEquals(EXPECTED_NFE, NFE_PLAN.extract(in));
            assertFalse(closed[0], "the stream lifecycle stays with the caller");
        }

        @Test
        void sessionsAreReusableAndRecoverFromErrors() {
            final byte[] doc = fixture("/fixtures/invoice/35240638167943000186550010000274361328488329-procNFe.xml");
            final XmlMappingEngine<Nfe> session = NFE_PLAN.newEngine();
            assertEquals(EXPECTED_NFE, session.extract(doc));
            assertEquals(EXPECTED_NFE, session.extract(doc));
            assertThrows(XmlException.class, () -> session.extract("<nfeProc><NFe>"));
            assertEquals(EXPECTED_NFE, session.extract(doc));
        }

        @Test
        void unmatchedRootYieldsAnEmptyResultNotAnError() {
            final Nfe nfe = NFE_PLAN.extract("<somethingElse><x>1</x></somethingElse>");
            assertNull(nfe.id());
            assertTrue(nfe.items().isEmpty());
        }
    }

    @Nested
    @DisplayName("Public facade")
    class PublicFacade {

        record Point(Integer x, Integer y) {}

        // Everything else in this file reaches the engine through the
        // package-private builder; this pins the public entry points a
        // library user actually calls: Xml.mapping + the extract overloads.
        @Test
        void xmlMappingAndEveryExtractOverloadAgree() {
            final class Draft { Integer x, y; }
            final XmlMapping<Point> plan = Xml.mapping(Draft::new)
                    .text("/p/x", (d, v) -> d.x = v.asInt())
                    .text("/p/y", (d, v) -> d.y = v.asInt())
                    .build(d -> new Point(d.x, d.y));

            final String xml = "<p><x>1</x><y>2</y></p>";
            final Point expected = new Point(1, 2);
            assertEquals(expected, Xml.extract(xml, plan));
            assertEquals(expected, Xml.extract(xml.getBytes(StandardCharsets.UTF_8), plan));
            assertEquals(expected, Xml.extract(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), plan));
        }
    }

    @Nested
    @DisplayName("Declaration order and repeats")
    class OrderAndRepeats {

        @Test
        void reorderedDocumentsProduceTheSameResult() {
            record Address(String city, String zip) {}
            final class Draft { String city, zip; }
            final XmlMapping<Address> plan = XmlMapping.builder(Draft::new)
                    .text("/address/city", (d, v) -> d.city = v.asString())
                    .text("/address/zip", (d, v) -> d.zip = v.asString())
                    .build(d -> new Address(d.city, d.zip));

            final Address expected = new Address("Curitiba", "80000-000");
            assertEquals(expected, plan.extract(fixture("/fixtures/order/address-natural.xml")));
            assertEquals(expected, plan.extract(fixture("/fixtures/order/address-reordered.xml")));
        }

        @Test
        void fullyReversedDocumentFillsAllSlots() {
            final class Draft { Integer a, b, c, d; }
            final XmlMapping<int[]> plan = XmlMapping.builder(Draft::new)
                    .text("/person/a", (d, v) -> d.a = v.asInt())
                    .text("/person/b", (d, v) -> d.b = v.asInt())
                    .text("/person/c", (d, v) -> d.c = v.asInt())
                    .text("/person/d", (d, v) -> d.d = v.asInt())
                    .build(d -> new int[] {d.a, d.b, d.c, d.d});

            assertEquals("[1, 2, 3, 4]", java.util.Arrays.toString(
                    plan.extract(fixture("/fixtures/order/reverse-full.xml"))));
        }

        @Test
        void repeatedLeafValuesAccumulateInDocumentOrder() {
            final XmlMapping<List<String>> plan = XmlMapping.<List<String>>builder(ArrayList::new)
                    .text("/tags/tag", (d, v) -> d.add(v.asString()))
                    .build(d -> d);
            assertEquals(List.of("a", "b", "c"), plan.extract(fixture("/fixtures/order/duplicates.xml")));
        }

        @Test
        void missingMiddleElementLeavesItsSlotNull() {
            final class Draft { Integer a, b, c; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/b", (d, v) -> d.b = v.asInt())
                    .text("/r/a", (d, v) -> d.a = v.asInt())
                    .text("/r/c", (d, v) -> d.c = v.asInt())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/order/optional-missing-middle.xml"));
            assertEquals(1, d.a);
            assertNull(d.b);
            assertEquals(3, d.c);
        }

        @Test
        void nestedBlockAndEarlierSiblingBothBind() {
            final class Draft { Integer first, x; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/second/x", (d, v) -> d.x = v.asInt())
                    .text("/r/first", (d, v) -> d.first = v.asInt())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/order/nested-before-sibling.xml"));
            assertEquals(1, d.first);
            assertEquals(9, d.x);
        }

        @Test
        void wideSiblingsBindRegardlessOfDeclarationOrder() {
            final class Draft { Integer n20, n1, n10; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/list/n20", (d, v) -> d.n20 = v.asInt())
                    .text("/list/n1", (d, v) -> d.n1 = v.asInt())
                    .text("/list/n10", (d, v) -> d.n10 = v.asInt())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/structure/wide-siblings.xml"));
            assertEquals(20, d.n20);
            assertEquals(1, d.n1);
            assertEquals(10, d.n10);
        }
    }

    @Nested
    @DisplayName("Groups")
    class Groups {

        record Employee(String firstName, String lastName) {}

        @Test
        void groupCollectsOneDraftPerOccurrence() {
            final class Dept { String name; final List<Employee> employees = new ArrayList<>(); }
            final class Emp { String first, last; }
            final XmlMapping<Dept> plan = XmlMapping.builder(Dept::new)
                    .group("/company/department/employees/employee", Emp::new,
                            (d, e) -> d.employees.add(new Employee(e.first, e.last)))
                        .text("firstName", (e, v) -> e.first = v.asString())
                        .text("lastName", (e, v) -> e.last = v.asString())
                        .endGroup()
                    .text("/company/department/name", (d, v) -> d.name = v.asString())
                    .build(d -> d);

            final Dept dept = plan.extract(fixture("/fixtures/order/deep-reordered.xml"));
            assertEquals("Engineering", dept.name);
            assertEquals(List.of(new Employee("Ana", "Souza"), new Employee("Bruno", "Lima")), dept.employees);
        }

        @Test
        void realisticInvoiceWithNestedStructuresAndAttributes() {
            record Item(String sku, Integer qty) {}
            record Invoice(Long number, boolean urgent, String customer, String city, String zip,
                           List<Item> items, BigDecimal total) {}
            final class Draft {
                Long number; Boolean urgent; String customer, city, zip; BigDecimal total;
                final List<Item> items = new ArrayList<>();
            }
            final class ItemD { String sku; Integer qty; }

            final XmlMapping<Invoice> plan = XmlMapping.builder(Draft::new)
                    .text("/invoice/total", (d, v) -> d.total = v.asDecimal())
                    .text("/invoice/customer/name", (d, v) -> d.customer = v.asString())
                    .text("/invoice/customer/address/city", (d, v) -> d.city = v.asString())
                    .text("/invoice/customer/address/zip", (d, v) -> d.zip = v.asString())
                    .group("/invoice/items/item", ItemD::new, (d, i) -> d.items.add(new Item(i.sku, i.qty)))
                        .text("sku", (i, v) -> i.sku = v.asString())
                        .text("qty", (i, v) -> i.qty = v.asInt())
                        .endGroup()
                    .attr("/invoice@number", (d, v) -> d.number = v.asLong())
                    .attr("/invoice@urgent", (d, v) -> d.urgent = v.asBoolean())
                    .build(d -> new Invoice(d.number, Boolean.TRUE.equals(d.urgent), d.customer, d.city, d.zip,
                            d.items, d.total));

            final Invoice expected = new Invoice(1042L, true, "Ana Souza", "Curitiba", "80000-000",
                    List.of(new Item("A-100", 2), new Item("B-200", 1)), new BigDecimal("150.00"));
            assertEquals(expected, plan.extract(fixture("/fixtures/realistic/invoice.xml")));
        }

        @Test
        void selfClosingGroupOccurrencesCommitWithAttributesOnly() {
            final XmlMapping<List<String>> plan = XmlMapping.<List<String>>builder(ArrayList::new)
                    .group("/r/item", () -> new String[1], (d, i) -> d.add(i[0]))
                        .attr("@sku", (i, v) -> i[0] = v.asString())
                        .endGroup()
                    .build(d -> d);
            final List<String> skus = plan.extract("<r><item/><item sku=\"X\"/><item sku=\"Y\"></item></r>");
            assertEquals(3, skus.size());
            assertNull(skus.get(0));
            assertEquals("X", skus.get(1));
            assertEquals("Y", skus.get(2));
        }

        @Test
        void nestedGroupsCommitInnerOccurrencesToTheEnclosingDraft() {
            record Order(String id, List<List<String>> batches) {}
            final class OrderD { String id; final List<List<String>> batches = new ArrayList<>(); }
            final XmlMapping<Order> plan = XmlMapping.builder(OrderD::new)
                    .attr("/order@id", (d, v) -> d.id = v.asString())
                    .group("/order/batch", () -> new ArrayList<String>(), (d, b) -> d.batches.add(b))
                        .group("item", () -> new String[1], (b, i) -> b.add(i[0]))
                            .text("sku", (i, v) -> i[0] = v.asString())
                            .endGroup()
                        .endGroup()
                    .build(d -> new Order(d.id, d.batches));

            final Order order = plan.extract("""
                    <order id="7">
                      <batch><item><sku>A</sku></item><item><sku>B</sku></item></batch>
                      <batch><item><sku>C</sku></item></batch>
                    </order>""");
            assertEquals(new Order("7", List.of(List.of("A", "B"), List.of("C"))), order);
        }

        @Test
        void firstOfInsideAGroupResetsPerOccurrence() {
            final XmlMapping<List<String>> plan = XmlMapping.<List<String>>builder(ArrayList::new)
                    .group("/r/p", () -> new String[1], (d, p) -> d.add(p[0]))
                        .firstOf((p, v) -> p[0] = v.asString(), "cpf", "cnpj")
                        .endGroup()
                    .build(d -> d);
            assertEquals(List.of("1", "2"),
                    plan.extract("<r><p><cpf>1</cpf></p><p><cnpj>2</cnpj></p></r>"));
        }
    }

    @Nested
    @DisplayName("Choices, required and early exit")
    class ChoicesAndRequired {

        @Test
        void firstOfBindsWhicheverAlternativeAppearsFirst() {
            final class Draft { String tax, name; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .firstOf((d, v) -> d.tax = v.asString(), "/dest/CPF", "/dest/CNPJ")
                    .text("/dest/name", (d, v) -> d.name = v.asString())
                    .build(d -> d);

            final Draft first = plan.extract(fixture("/fixtures/order/choice-cnpj-first.xml"));
            assertEquals("111", first.tax);
            assertEquals("ACME", first.name);

            final Draft second = plan.extract(fixture("/fixtures/order/choice-cpf-after.xml"));
            assertEquals("222", second.tax);
            assertEquals("ACME", second.name);
        }

        @Test
        void firstOfIgnoresLaterAlternativesWhenBothArePresent() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .firstOf((d, v) -> d[0] = v.asString(), "/dest/CPF", "/dest/CNPJ")
                    .build(d -> d);
            assertEquals("first", plan.extract("<dest><CNPJ>first</CNPJ><CPF>second</CPF></dest>")[0]);
        }

        @Test
        void requiredSlotsStopTheScanOnceSatisfied() {
            final class Draft { Integer a, b; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/a", (d, v) -> d.a = v.asInt())
                    .text("/r/b", (d, v) -> d.b = v.asInt())
                    .required("/r/a")
                    .build(d -> d);
            final Draft d = plan.extract("<r><a>1</a><b>2</b></r>");
            assertEquals(1, d.a);
            assertNull(d.b, "the engine must stop reading once required slots are bound");
        }
    }

    @Nested
    @DisplayName("Text content")
    class TextContent {

        @Test
        void cdataIsReadLiterally() {
            assertEquals("5 < 6 & 7 > 3", leafText("/fixtures/text/cdata.xml", "/r/expr"));
        }

        @Test
        void entitiesAreDecoded() {
            assertEquals("a < b && c > d", leafText("/fixtures/text/entities.xml", "/r/expr"));
        }

        @Test
        void mixedContentConcatenatesNestedText() {
            assertEquals("hello world!", leafText("/fixtures/text/mixed-content.xml", "/message"));
        }

        @Test
        void surroundingWhitespaceTrimmedAndBlankNeverBinds() {
            final class Draft { String padded, blank; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/padded", (d, v) -> d.padded = v.asString())
                    .text("/r/blank", (d, v) -> d.blank = v.asString())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/text/whitespace.xml"));
            assertEquals("hi", d.padded);
            assertNull(d.blank);
        }

        @Test
        void selfClosingAndEmptyElementsNeverBind() {
            final class Draft { String selfClosing, empty, full; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/selfClosing", (d, v) -> d.selfClosing = v.asString())
                    .text("/r/empty", (d, v) -> d.empty = v.asString())
                    .text("/r/full", (d, v) -> d.full = v.asString())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/text/empty-elements.xml"));
            assertNull(d.selfClosing);
            assertNull(d.empty);
            assertEquals("x", d.full);
        }

        @Test
        void unicodeContentPreserved() {
            assertEquals("São Paulo — Ação Ñoño 日本語 ✓", leafText("/fixtures/text/unicode.xml", "/r/name"));
        }

        @Test
        void numericCharacterReferencesDecode() {
            assertEquals("A€😀", leafValue("<v>&#65;&#x20AC;&#128512;</v>", XmlValue::asString));
        }

        @Test
        void carriageReturnsNormalizeToNewlines() {
            assertEquals("a\nb\nc", leafValue("<v>a\r\nb\rc</v>", XmlValue::asString));
        }

        private String leafText(final String fixturePath, final String path) {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text(path, (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            return plan.extract(fixture(fixturePath))[0];
        }
    }

    @Nested
    @DisplayName("Structure and attributes")
    class StructureAndAttributes {

        @Test
        void deeplyNestedSingleChild() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text("/l1/l2/l3/l4/l5/v", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            assertEquals("deep", plan.extract(fixture("/fixtures/structure/deep-nesting.xml"))[0]);
        }

        @Test
        void defaultNamespaceMatchesByPlainName() {
            final class Draft { Integer id; BigDecimal total; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/order/id", (d, v) -> d.id = v.asInt())
                    .text("/order/total", (d, v) -> d.total = v.asDecimal())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/structure/namespace-default.xml"));
            assertEquals(7, d.id);
            assertEquals(new BigDecimal("99.90"), d.total);
        }

        @Test
        void prefixedNamespaceMatchesByFullTagName() {
            final XmlMapping<Integer[]> plan = XmlMapping.builder(() -> new Integer[1])
                    .text("/ns:order/ns:id", (d, v) -> d[0] = v.asInt())
                    .build(d -> d);
            assertEquals(7, plan.extract(fixture("/fixtures/structure/namespace-prefixed.xml"))[0]);
        }

        @Test
        void attributesAndElementTextBindTogether() {
            final class Draft { Integer id; BigDecimal total; Boolean urgent; String ref, sku, content; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .attr("/order/item@sku", (d, v) -> d.sku = v.asString())
                    .text("/order/item", (d, v) -> d.content = v.asString())
                    .attr("/order@id", (d, v) -> d.id = v.asInt())
                    .attr("/order@total", (d, v) -> d.total = v.asDecimal())
                    .attr("/order@urgent", (d, v) -> d.urgent = v.asBoolean())
                    .attr("/order@ref", (d, v) -> d.ref = v.asString())
                    .build(d -> d);
            final Draft d = plan.extract(fixture("/fixtures/structure/attributes.xml"));
            assertEquals(7, d.id);
            assertEquals(new BigDecimal("99.90"), d.total);
            assertEquals(Boolean.TRUE, d.urgent);
            assertEquals("ABC", d.ref);
            assertEquals("X-1", d.sku);
            assertEquals("content", d.content);
        }

        @Test
        void attributeEntitiesAndWhitespaceNormalize() {
            assertEquals("a b\"c", extractAttr("<r x=\"a\tb&quot;c\"/>"));
            assertEquals("tab\tkept", extractAttr("<r x=\"tab&#9;kept\"/>"));
        }

        private String extractAttr(final String xml) {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .attr("/r@x", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            return plan.extract(xml)[0];
        }
    }

    @Nested
    @DisplayName("Strict skip")
    class StrictSkip {

        private XmlMapping<String[]> mapping(final boolean strict) {
            final XmlMapping.Builder<String[]> b = XmlMapping.builder(() -> new String[1]);
            if (strict) b.strictSkip();
            return b.text("/r/want", (d, v) -> d[0] = v.asString()).build(d -> d);
        }

        @Test
        void skippedSubtreesAreOnlyCountedByDefault() {
            assertEquals("x", mapping(false).extract("<r><want>x</want><junk><a></b></junk></r>")[0]);
        }

        @Test
        void strictSkipRejectsMismatchedEndTagsInsideSkippedSubtrees() {
            final XmlException e = assertThrows(XmlException.class,
                    () -> mapping(true).extract("<r><want>x</want><junk><a></b></junk></r>"));
            assertTrue(e.getMessage().contains("Mismatched end tag"), e.getMessage());
        }

        @Test
        void strictSkipRejectsAnEndTagClosingTheSkippedElementItself() {
            final XmlException e = assertThrows(XmlException.class,
                    () -> mapping(true).extract("<r><want>x</want><junk></wrong></r>"));
            assertTrue(e.getMessage().contains("Mismatched end tag"), e.getMessage());
        }

        @Test
        void strictSkipAcceptsWellFormedSkippedSubtrees() {
            assertEquals("x", mapping(true).extract(
                    "<r><want>x</want><junk a=\"b>c\"><d><e/><f>t</f></d><!--c--><?p?></junk></r>")[0]);
        }

        @Test
        void strictSkipVerifiesSubtreesDeeperThanTheInitialStack() {
            final StringBuilder sb = new StringBuilder("<r><want>x</want><junk>");
            for (int k = 0; k < 40; k++) sb.append("<n").append(k).append('>');
            for (int k = 39; k >= 0; k--) sb.append("</n").append(k).append('>');
            sb.append("</junk></r>");
            assertEquals("x", mapping(true).extract(sb.toString())[0]);
        }

        @Test
        void strictSkipHoldsAcrossWindowRefills() {
            final String xml = "<r><want>x</want><junk><deep-element-name>t</deep-element-name></junk></r>";
            final byte[] doc = xml.getBytes(StandardCharsets.UTF_8);
            for (long seed = 0; seed < 20; seed++) {
                assertEquals("x", new XmlMappingEngine<>(mapping(true), 16)
                        .extract(new Drip(doc, seed))[0], "seed " + seed);
            }
            final byte[] bad = xml.replace("</deep-element-name>", "</other-element-name>")
                    .getBytes(StandardCharsets.UTF_8);
            for (long seed = 0; seed < 20; seed++) {
                final long s = seed;
                assertThrows(XmlException.class, () -> new XmlMappingEngine<>(mapping(true), 16)
                        .extract(new Drip(bad, s)), "seed " + seed);
            }
        }
    }

    @Nested
    @DisplayName("Streaming")
    class Streaming {

        @Test
        void dripFedStreamWithTinyWindowMatchesByteArrayExtraction() {
            final byte[] doc = fixture("/fixtures/invoice/35240638167943000186550010000274361328488329-procNFe.xml");
            final XmlMappingEngine<Nfe> tiny = new XmlMappingEngine<>(NFE_PLAN, 64);
            assertEquals(EXPECTED_NFE, tiny.extract(new Drip(doc, 7)));
            assertEquals(EXPECTED_NFE, tiny.extract(new Drip(doc, 8)), "sessions reuse the window across documents");
        }

        @Test
        void documentsFarLargerThanTheWindowStreamThrough() {
            final StringBuilder sb = new StringBuilder("<r>");
            for (int k = 0; k < 20_000; k++) {
                sb.append("<i>").append(k % 10).append("</i><skip attr=\"a>b\"><x>y</x></skip>");
            }
            sb.append("</r>");
            final byte[] doc = sb.toString().getBytes(StandardCharsets.UTF_8);

            final XmlMapping<int[]> plan = XmlMapping.builder(() -> new int[2])
                    .text("/r/i", (d, v) -> {
                        d[0]++;
                        d[1] += v.asInt();
                    })
                    .build(d -> d);
            final int[] viaBytes = plan.extract(doc);
            final int[] viaStream = new XmlMappingEngine<>(plan, 64).extract(new Drip(doc, 42));
            assertEquals(20_000, viaStream[0]);
            assertEquals(viaBytes[0], viaStream[0]);
            assertEquals(viaBytes[1], viaStream[1]);
        }

        @Test
        void tokensStraddlingRefillsSurviveEntitiesCrlfAndCdata() {
            final String xml = "<r><v>alpha &amp; beta\r\ngamma &#128512;</v><w>a<![CDATA[x < y]]>b</w></r>";
            final class Draft { String v, w; }
            final XmlMapping<Draft> plan = XmlMapping.builder(Draft::new)
                    .text("/r/v", (d, v) -> d.v = v.asString())
                    .text("/r/w", (d, v) -> d.w = v.asString())
                    .build(d -> d);
            final Draft expected = plan.extract(xml.getBytes(StandardCharsets.UTF_8));
            assertEquals("alpha & beta\ngamma 😀", expected.v);
            assertEquals("ax < yb", expected.w);
            for (long seed = 0; seed < 20; seed++) {
                final Draft streamed = new XmlMappingEngine<>(plan, 16)
                        .extract(new Drip(xml.getBytes(StandardCharsets.UTF_8), seed));
                assertEquals(expected.v, streamed.v, "seed " + seed);
                assertEquals(expected.w, streamed.w, "seed " + seed);
            }
        }

        @Test
        void legacyEncodingStreamsFallBackToFullReadAndStillExtract() {
            final XmlMapping<String[]> latinPlan = XmlMapping.builder(() -> new String[1])
                    .text("/city/name", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            final byte[] latin = fixture("/fixtures/encoding/iso-8859-1.xml");
            assertEquals("São Paulo", new XmlMappingEngine<>(latinPlan, 64).extract(new Drip(latin, 3))[0]);

            final XmlMapping<String[]> utfPlan = XmlMapping.builder(() -> new String[1])
                    .text("/r/name", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            final byte[] utf16 = "<r><name>Ação ✓</name></r>".getBytes(StandardCharsets.UTF_16);
            assertEquals("Ação ✓", new XmlMappingEngine<>(utfPlan, 64).extract(new Drip(utf16, 4))[0]);
        }

        @Test
        void truncatedStreamsThrow() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text("/book/title", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            final byte[] cut = "<book><title>Du".getBytes(StandardCharsets.UTF_8);
            assertThrows(XmlException.class,
                    () -> new XmlMappingEngine<>(plan, 64).extract(new Drip(cut, 5)));
        }
    }

    @Nested
    @DisplayName("Encodings")
    class Encodings {

        @Test
        void latin1AutoDetectedFromDeclaration() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text("/city/name", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            assertEquals("São Paulo", plan.extract(fixture("/fixtures/encoding/iso-8859-1.xml"))[0]);
        }

        @Test
        void utf16WithBomIsTranscoded() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text("/r/name", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            final byte[] utf16 = "<r><name>Ação ✓</name></r>".getBytes(StandardCharsets.UTF_16);
            assertEquals("Ação ✓", plan.extract(utf16)[0]);
        }

        @Test
        void unsupportedEncodingIsRejectedLoudly() {
            final XmlMapping<String[]> plan = XmlMapping.builder(() -> new String[1])
                    .text("/r/a", (d, v) -> d[0] = v.asString())
                    .build(d -> d);
            final XmlException e = assertThrows(XmlException.class, () -> plan.extract(
                    "<?xml version=\"1.0\" encoding=\"EBCDIC-CP-US\"?><r><a>x</a></r>"
                            .getBytes(StandardCharsets.ISO_8859_1)));
            assertTrue(e.getMessage().contains("EBCDIC-CP-US"));
        }
    }

    @Nested
    @DisplayName("Malformed input")
    class Errors {

        private XmlMapping<String[]> leafPlan(final String path) {
            return XmlMapping.builder(() -> new String[1])
                    .text(path, (d, v) -> d[0] = v.asString())
                    .build(d -> d);
        }

        @Test
        void mismatchedTagsThrow() {
            assertThrows(XmlException.class,
                    () -> leafPlan("/book/title").extract(fixture("/fixtures/errors/mismatched-tags.xml")));
        }

        @Test
        void truncatedDocumentThrows() {
            assertThrows(XmlException.class,
                    () -> leafPlan("/book/title").extract(fixture("/fixtures/errors/truncated.xml")));
        }

        @Test
        void undeclaredEntityThrows() {
            assertThrows(XmlException.class,
                    () -> leafPlan("/a/b").extract(fixture("/fixtures/errors/undeclared-entity.xml")));
        }

        @Test
        void doctypeIsRejected() {
            assertThrows(XmlException.class,
                    () -> leafPlan("/r/a").extract(fixture("/fixtures/errors/xxe-doctype.xml")));
        }

        @Test
        void mismatchedEndTagOnTheTraversedPathThrows() {
            assertThrows(XmlException.class,
                    () -> leafPlan("/a/c").extract("<a><c>x</wrong></a>"));
        }

        @Test
        void skippedSubtreesAreOnlyBalanceChecked() {
            // Documented leniency: elements outside the plan are crossed by a
            // depth-counting skip, so a mismatched name confined to a skipped
            // region goes unnoticed as long as the tags balance.
            assertNull(leafPlan("/a/b").extract("<a><c>x</wrong></a>")[0]);
        }

        @Test
        void emptyInputThrows() {
            assertThrows(XmlException.class, () -> leafPlan("/a/b").extract(new byte[0]));
            assertThrows(XmlException.class, () -> leafPlan("/a/b").extract("   "));
        }
    }

    @Nested
    @DisplayName("XmlValue accessors")
    class Values {

        @Test
        void longsParseFromBytesIncludingExtremes() {
            assertEquals(27436L, leafValue("<v>27436</v>", XmlValue::asLong));
            assertEquals(-9_999_999_999L, leafValue("<v>-9999999999</v>", XmlValue::asLong));
            assertEquals(Long.MAX_VALUE, leafValue("<v>9223372036854775807</v>", XmlValue::asLong));
            assertEquals(Long.MIN_VALUE, leafValue("<v>-9223372036854775808</v>", XmlValue::asLong));
            assertEquals(7L, leafValue("<v>+7</v>", XmlValue::asLong));
            assertThrows(NumberFormatException.class, () -> leafValue("<v>12x</v>", XmlValue::asLong));
        }

        @Test
        void intsParseNegativesAndRejectOverflow() {
            assertEquals(-42, leafValue("<v>-42</v>", XmlValue::asInt));
            assertThrows(NumberFormatException.class, () -> leafValue("<v>3000000000</v>", XmlValue::asInt));
        }

        @Test
        void decimalsMatchTheExactConstructor() {
            assertEquals(new BigDecimal("10.50"), leafValue("<v>10.50</v>", XmlValue::asDecimal));
            assertEquals(new BigDecimal("-12345.6789"), leafValue("<v>-12345.6789</v>", XmlValue::asDecimal));
            assertEquals(new BigDecimal("0"), leafValue("<v>0</v>", XmlValue::asDecimal));
            assertEquals(new BigDecimal("12345678901234567890.5"),
                    leafValue("<v>12345678901234567890.5</v>", XmlValue::asDecimal));
            assertEquals(new BigDecimal("1e3"), leafValue("<v>1e3</v>", XmlValue::asDecimal));
            assertThrows(NumberFormatException.class, () -> leafValue("<v>abc</v>", XmlValue::asDecimal));
        }

        @Test
        void doublesParseAndEnumsMatchByConstantName() {
            assertEquals(-1.5, leafValue("<v>-1.5</v>", XmlValue::asDouble));
            assertEquals(Status.ACTIVE, leafValue("<v>ACTIVE</v>", v -> v.asEnum(Status.class)));
            assertThrows(IllegalArgumentException.class,
                    () -> leafValue("<v>NOPE</v>", v -> v.asEnum(Status.class)));
        }

        @Test
        void booleansAcceptTheCursorAlphabet() {
            assertEquals(Boolean.TRUE, leafValue("<v>true</v>", XmlValue::asBoolean));
            assertEquals(Boolean.TRUE, leafValue("<v>TRUE</v>", XmlValue::asBoolean));
            assertEquals(Boolean.TRUE, leafValue("<v>1</v>", XmlValue::asBoolean));
            assertEquals(Boolean.FALSE, leafValue("<v>False</v>", XmlValue::asBoolean));
            assertEquals(Boolean.FALSE, leafValue("<v>0</v>", XmlValue::asBoolean));
            assertThrows(XmlException.class, () -> leafValue("<v>yes</v>", XmlValue::asBoolean));
        }

        @Test
        void canonicalReturnsTheSameInstanceWithinASession() {
            final XmlMapping<List<String>> plan = XmlMapping.<List<String>>builder(ArrayList::new)
                    .text("/r/uf", (d, v) -> d.add(v.asCanonical()))
                    .build(d -> d);
            final XmlMappingEngine<List<String>> session = plan.newEngine();
            final List<String> first = session.extract("<r><uf>SP</uf><uf>SP</uf><uf>SC</uf></r>");
            assertEquals(List.of("SP", "SP", "SC"), first);
            assertSame(first.get(0), first.get(1));

            final List<String> second = session.extract("<r><uf>SP</uf></r>");
            assertSame(first.get(0), second.get(0), "the cache spans documents within a session");
        }

        @Test
        void canonicalMatchesByDecodedContentAndCapsBySize() {
            final XmlMapping<List<String>> plan = XmlMapping.<List<String>>builder(ArrayList::new)
                    .text("/r/v", (d, v) -> d.add(v.asCanonical()))
                    .build(d -> d);
            final XmlMappingEngine<List<String>> session = plan.newEngine();
            // "S&#80;" decodes through the cook buffer; it must canonicalize
            // to the same instance as the plain span "SP".
            final List<String> decoded = session.extract("<r><v>S&#80;</v><v>SP</v></r>");
            assertEquals(List.of("SP", "SP"), decoded);
            assertSame(decoded.get(0), decoded.get(1));

            final String big = "x".repeat(80);
            assertEquals(List.of(big, big), session.extract("<r><v>" + big + "</v><v>" + big + "</v></r>"));
        }

        @Test
        void instantsMatchInstantParseAcrossLayouts() {
            for (final String iso : new String[] {
                    "2026-01-15T10:30:00Z",
                    "2024-06-20T11:27:22-03:00",
                    "2024-06-20T11:27:22.123-03:00",
                    "2024-02-29T23:59:59.999999999Z",
                    "1969-12-31T23:59:59+00:30",
            }) {
                assertEquals(Instant.parse(iso), leafValue("<v>" + iso + "</v>", XmlValue::asInstant), iso);
            }
            assertThrows(DateTimeParseException.class,
                    () -> leafValue("<v>not-a-date</v>", XmlValue::asInstant));
            assertThrows(DateTimeParseException.class,
                    () -> leafValue("<v>2024-06-31T10:00:00Z</v>", XmlValue::asInstant));
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        void pathsMustBeAbsoluteAtTheRootAndRelativeInGroups() {
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .text("a/b", (d, v) -> {}));
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .group("/r/g", Object::new, (d, g) -> {})
                    .text("/abs", (g, v) -> {}));
        }

        @Test
        void duplicateAndConflictingTargetsAreRejected() {
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .text("/a/b", (d, v) -> {})
                    .text("/a/b", (d, v) -> {})
                    .build(d -> d));
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .text("/a", (d, v) -> {})
                    .text("/a/b", (d, v) -> {})
                    .build(d -> d));
        }

        @Test
        void attributePathsRequireTheAtSign() {
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .attr("/a/b", (d, v) -> {}));
        }

        @Test
        void foreignPathsCannotCrossAGroup() {
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .group("/r/item", Object::new, (d, g) -> {})
                    .endGroup()
                    .text("/r/item/name", (d, v) -> {})
                    .build(d -> d));
        }

        @Test
        void requiredMustReferenceDeclaredNonGroupPaths() {
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .required("/never/declared"));
            assertThrows(XmlException.class, () -> XmlMapping.builder(Object::new)
                    .group("/r/g", Object::new, (d, g) -> {})
                    .text("x", (g, v) -> {})
                    .endGroup()
                    .required("/r/g/x"));
        }

        @Test
        void plansCapAtSixtyFourFields() {
            final XmlMapping.Builder<Object> builder = XmlMapping.builder(Object::new);
            for (int i = 0; i < 64; i++) {
                builder.text("/r/f" + i, (d, v) -> {});
            }
            assertThrows(XmlException.class, () -> builder.text("/r/overflow", (d, v) -> {}));
        }
    }
}
