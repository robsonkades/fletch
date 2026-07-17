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

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.io.Stax2ByteArraySource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NF-e extraction: the compiled-plan engine versus the cursor API and a bare
 * Woodstox event loop, on the repository's real procNFe fixture scaled to
 * 1 / 50 / 500 {@code <det>} items.
 *
 * <p>Build and run:
 * <pre>{@code
 * mvn -P benchmarks package -DskipTests -Dgpg.skip=true
 * java -jar target/benchmarks.jar ExtractionBenchmark -prof gc
 * }</pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class ExtractionBenchmark {

    record NfeIde(Long number, Instant issuedAt) {}
    record NfeParty(String taxId, String name, String uf) {}
    record NfeItem(Integer number, String code, String description, BigDecimal amount) {}
    record NfeProtocol(String accessKey, Integer status, String reason) {}
    record Nfe(String id, NfeIde ide, NfeParty emit, NfeParty dest,
               List<NfeItem> items, BigDecimal totalAmount, NfeProtocol protocol) {}

    // ------------------------------------------------------------------ compiled plan

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
    }

    static final XmlMapping<Nfe> PLAN = nfePlan(false);

    /** Same paths, but end tags inside the subtrees the plan never selects are verified. */
    static final XmlMapping<Nfe> PLAN_STRICT = nfePlan(true);

    private static XmlMapping<Nfe> nfePlan(final boolean strict) {
        final XmlMapping.Builder<NfeDraft> b = XmlMapping.builder(NfeDraft::new);
        if (strict) b.strictSkip();
        return b
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
            .group("/nfeProc/NFe/infNFe/det", ItemDraft::new,
                    (d, i) -> d.items.add(new NfeItem(i.n, i.code, i.description, i.amount)))
                .attr("@nItem", (i, v) -> i.n = v.asInt())
                .text("prod/cProd", (i, v) -> i.code = v.asString())
                .text("prod/xProd", (i, v) -> i.description = v.asString())
                .text("prod/vProd", (i, v) -> i.amount = v.asDecimal())
                .endGroup()
            .text("/nfeProc/NFe/infNFe/total/ICMSTot/vNF", (d, v) -> d.total = v.asDecimal())
            .text("/nfeProc/protNFe/infProt/chNFe", (d, v) -> d.accessKey = v.asString())
            .text("/nfeProc/protNFe/infProt/cStat", (d, v) -> d.status = v.asInt())
            .text("/nfeProc/protNFe/infProt/xMotivo", (d, v) -> d.reason = v.asString())
            .build(NfeDraft::toNfe);
    }

    // ------------------------------------------------------------------ cursor extractors (Fletch 1.0)

    static final XmlExtractor<NfeIde> IDE = i -> new NfeIde(
            i.value("nNF", Long.class),
            i.value("dhEmi", Instant.class));

    static final XmlExtractor<NfeParty> EMIT = e -> new NfeParty(
            e.value("CNPJ", String.class),
            e.value("xNome", String.class),
            e.child("enderEmit", a -> a.value("UF", String.class)));

    static final XmlExtractor<NfeParty> DEST = d -> new NfeParty(
            d.firstOf(String.class, "CPF", "CNPJ", "idEstrangeiro"),
            d.value("xNome", String.class),
            d.child("enderDest", a -> a.value("UF", String.class)));

    static final XmlExtractor<NfeItem> ITEM = det -> det.child("prod", p -> new NfeItem(
            det.attribute("nItem", Integer.class),
            p.value("cProd", String.class),
            p.value("xProd", String.class),
            p.value("vProd", BigDecimal.class)));

    static final XmlExtractor<NfeProtocol> PROTOCOL = ip -> new NfeProtocol(
            ip.value("chNFe", String.class),
            ip.value("cStat", Integer.class),
            ip.value("xMotivo", String.class));

    /** Fields requested in document order — the cursor API's best case. */
    static final XmlExtractor<Nfe> CURSOR_DOC_ORDER = doc -> doc.child("nfeProc", np -> {
        final Nfe base = np.child("NFe", n -> n.child("infNFe", inf -> new Nfe(
                inf.attribute("Id", String.class),
                inf.child("ide", IDE),
                inf.child("emit", EMIT),
                inf.child("dest", DEST),
                inf.children("det", ITEM),
                inf.child("total", t -> t.child("ICMSTot", ic -> ic.value("vNF", BigDecimal.class))),
                null)));
        final NfeProtocol protocol = np.child("protNFe", pr -> pr.child("infProt", PROTOCOL));
        return new Nfe(base.id(), base.ide(), base.emit(), base.dest(), base.items(), base.totalAmount(), protocol);
    });

    /** Protocol requested first — buffers the whole NFe subtree. */
    static final XmlExtractor<Nfe> CURSOR_AS_WRITTEN = doc -> doc.child("nfeProc", np -> {
        final NfeProtocol protocol = np.child("protNFe", pr -> pr.child("infProt", PROTOCOL));
        return np.child("NFe", n -> n.child("infNFe", inf -> new Nfe(
                inf.attribute("Id", String.class),
                inf.child("ide", IDE),
                inf.child("emit", EMIT),
                inf.child("dest", DEST),
                inf.children("det", ITEM),
                inf.child("total", t -> t.child("ICMSTot", ic -> ic.value("vNF", BigDecimal.class))),
                protocol)));
    });

    // ------------------------------------------------------------------ bare Woodstox floor

    static final WstxInputFactory RAW;

    static {
        RAW = new WstxInputFactory();
        RAW.setProperty(XMLInputFactory2.P_LAZY_PARSING, true);
        RAW.setProperty(XMLInputFactory2.SUPPORT_DTD, false);
        RAW.setProperty(XMLInputFactory2.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        RAW.setProperty(XMLInputFactory2.IS_COALESCING, false);
        RAW.setProperty(XMLInputFactory2.IS_NAMESPACE_AWARE, false);
        RAW.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, false);
        RAW.setProperty(XMLInputFactory2.P_REPORT_PROLOG_WHITESPACE, false);
        RAW.setProperty(WstxInputProperties.P_INPUT_BUFFER_LENGTH, 16_384);
        RAW.setProperty(WstxInputProperties.P_MIN_TEXT_SEGMENT, 64);
    }

    // ------------------------------------------------------------------ state

    @Param({"1", "50", "500"})
    public int dets;

    public byte[] doc;

    private XmlMappingEngine<Nfe> session;

    @Setup
    public void setup() throws IOException {
        final byte[] base;
        try (InputStream in = ExtractionBenchmark.class.getResourceAsStream(
                "/fixtures/invoice/35240612345678000195550010000274361328488326-procNFe.xml")) {
            if (in == null) throw new IllegalStateException("fixture not on classpath");
            base = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        doc = scale(base, dets);
        session = PLAN.newEngine();
        if (!PLAN.extract(doc).equals(Xml.extract(doc, CURSOR_DOC_ORDER))) {
            throw new IllegalStateException("plan and cursor disagree");
        }
        if (!PLAN.extract(doc).equals(PLAN_STRICT.extract(doc))) {
            throw new IllegalStateException("strict skip changes the result");
        }
    }

    private static byte[] scale(final byte[] base, final int dets) {
        final String s = new String(base, StandardCharsets.UTF_8);
        final int a = s.indexOf("<det ");
        final int z = s.indexOf("</det>") + "</det>".length();
        final String det = s.substring(a, z);
        return (s.substring(0, a) + det.repeat(dets) + s.substring(z)).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ benchmarks

    @Benchmark
    public Nfe plan() {
        return PLAN.extract(doc);
    }

    @Benchmark
    public Nfe planStrictSkip() {
        return PLAN_STRICT.extract(doc);
    }

    @Benchmark
    public Nfe planSession() {
        return session.extract(doc);
    }

    @Benchmark
    public Nfe cursorDocOrder() {
        return Xml.extract(doc, CURSOR_DOC_ORDER);
    }

    @Benchmark
    public Nfe cursorAsWritten() {
        return Xml.extract(doc, CURSOR_AS_WRITTEN);
    }

    @Benchmark
    public long woodstoxRawLoop() throws Exception {
        final XMLStreamReader r = RAW.createXMLStreamReader(new Stax2ByteArraySource(doc, 0, doc.length));
        long acc = 0;
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT) acc += r.getLocalName().length();
        }
        r.close();
        return acc;
    }
}
