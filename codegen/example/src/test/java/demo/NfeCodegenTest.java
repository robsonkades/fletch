package demo;

import demo.generated.NfeCode;
import io.github.robsonkades.fletch.Xml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class NfeCodegenTest {
    @Test
    void originalFixtureIsUnchanged() throws Exception {
        final byte[] bytes = NfeFixture.load(1);
        assertEquals(7112, bytes.length);
        assertEquals("14dce332766140d019ea7bde3fad46d69e73a9bbbf8f40f0d7c94980816000b5",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @ParameterizedTest @ValueSource(ints = {1, 50, 500})
    void completeModelMatchesIndependentOracleAcrossInputsAndSessions(final int items) throws Exception {
        final byte[] document = NfeFixture.load(items);
        final var expected = NfeFixture.expected(document);
        assertEquals(items, expected.items().size());
        final var dynamic = NfeDefinition.mapping();
        final var generated = NfeCode.mapping(dynamic);
        for (var mapping : java.util.List.of(dynamic, generated)) {
            assertEquals(expected, Xml.extract(document, mapping));
            assertEquals(expected, Xml.extract(new String(document, StandardCharsets.UTF_8), mapping));
            assertEquals(expected, Xml.extract(new ByteArrayInputStream(document), mapping));
            try (var session = mapping.openSession()) {
                assertEquals(expected, session.extract(document));
                assertEquals(expected, session.extract(document));
            }
        }
        final var benchmark = new NfeCodegenBenchmark();
        benchmark.items = items;
        benchmark.setup();
        assertEquals(expected, benchmark.dynamic());
        assertEquals(expected, benchmark.generated());
    }

    @ParameterizedTest @ValueSource(strings = {"CPF", "CNPJ", "idEstrangeiro", "missing"})
    void alternativesAndOptionalFieldsSurviveReorderedInput(final String choice) throws Exception {
        final String tax = choice.equals("missing") ? "" : "<" + choice + ">123456</" + choice + ">";
        final String document = """
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <protNFe><infProt><xMotivo>Autorização &amp; teste</xMotivo><cStat>100</cStat><chNFe>key</chNFe></infProt></protNFe>
                  <NFe><infNFe Id="example">
                    <total><ICMSTot><vNF>24.60</vNF></ICMSTot></total>
                    <det nItem="2"><prod><vProd>12.30</vProd><xProd>Item B</xProd><cProd>B</cProd></prod></det>
                    <dest><enderDest><UF>SP</UF></enderDest>%s</dest>
                    <emit><enderEmit><UF>SC</UF></enderEmit><xNome>Emissão</xNome><CNPJ>987</CNPJ></emit>
                    <det nItem="1"><prod><cProd>A</cProd><vProd>12.30</vProd></prod></det>
                    <ide><dhEmi>2026-09-17T15:30:00-03:00</dhEmi><nNF>42</nNF></ide>
                  </infNFe></NFe>
                </nfeProc>
                """.formatted(tax);
        final var expected = NfeFixture.expected(document.getBytes(StandardCharsets.UTF_8));
        final var dynamic = NfeDefinition.mapping();
        assertEquals(expected, Xml.extract(document, dynamic));
        assertEquals(expected, Xml.extract(document, NfeCode.mapping(dynamic)));
        assertNull(expected.dest().name());
        assertNull(expected.items().get(1).description());
    }
}
