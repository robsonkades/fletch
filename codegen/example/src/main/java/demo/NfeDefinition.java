package demo;

import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Same selected fields and output types as the core ExtractionBenchmark NF-e mapping. */
public final class NfeDefinition {
    private NfeDefinition() {}

    public record Ide(Long number, Instant issuedAt) {}
    public record Party(String taxId, String name, String uf) {}
    public record Item(Integer number, String code, String description, BigDecimal amount) {}
    public record Protocol(String accessKey, Integer status, String reason) {}
    public record Nfe(String id, Ide ide, Party emit, Party dest, List<Item> items,
                      BigDecimal totalAmount, Protocol protocol) {}

    private static final class Draft {
        String id, emitTax, emitName, emitUf, destTax, destName, destUf, accessKey, reason;
        Long number;
        Instant issuedAt;
        BigDecimal total;
        Integer status;
        final List<Item> items = new ArrayList<>();

        Nfe finish() {
            return new Nfe(id, new Ide(number, issuedAt), new Party(emitTax, emitName, emitUf),
                    new Party(destTax, destName, destUf), items, total,
                    new Protocol(accessKey, status, reason));
        }
    }

    private static final class ItemDraft {
        Integer number;
        String code, description;
        BigDecimal amount;
    }

    public static XmlMapping<Nfe> mapping() {
        return Xml.mapping(Draft::new)
                .attr("/nfeProc/NFe/infNFe@Id", (d, v) -> d.id = v.asString())
                .text("/nfeProc/NFe/infNFe/ide/nNF", (d, v) -> d.number = v.asLong())
                .text("/nfeProc/NFe/infNFe/ide/dhEmi", (d, v) -> d.issuedAt = v.asInstant())
                .text("/nfeProc/NFe/infNFe/emit/CNPJ", (d, v) -> d.emitTax = v.asString())
                .text("/nfeProc/NFe/infNFe/emit/xNome", (d, v) -> d.emitName = v.asString())
                .text("/nfeProc/NFe/infNFe/emit/enderEmit/UF", (d, v) -> d.emitUf = v.asString())
                .firstOf((d, v) -> d.destTax = v.asString(), "/nfeProc/NFe/infNFe/dest/CPF",
                        "/nfeProc/NFe/infNFe/dest/CNPJ", "/nfeProc/NFe/infNFe/dest/idEstrangeiro")
                .text("/nfeProc/NFe/infNFe/dest/xNome", (d, v) -> d.destName = v.asString())
                .text("/nfeProc/NFe/infNFe/dest/enderDest/UF", (d, v) -> d.destUf = v.asString())
                .group("/nfeProc/NFe/infNFe/det", ItemDraft::new,
                        (d, i) -> d.items.add(new Item(i.number, i.code, i.description, i.amount)))
                    .attr("@nItem", (i, v) -> i.number = v.asInt())
                    .text("prod/cProd", (i, v) -> i.code = v.asString())
                    .text("prod/xProd", (i, v) -> i.description = v.asString())
                    .text("prod/vProd", (i, v) -> i.amount = v.asDecimal())
                    .endGroup()
                .text("/nfeProc/NFe/infNFe/total/ICMSTot/vNF", (d, v) -> d.total = v.asDecimal())
                .text("/nfeProc/protNFe/infProt/chNFe", (d, v) -> d.accessKey = v.asString())
                .text("/nfeProc/protNFe/infProt/cStat", (d, v) -> d.status = v.asInt())
                .text("/nfeProc/protNFe/infProt/xMotivo", (d, v) -> d.reason = v.asString())
                .build(Draft::finish);
    }
}
