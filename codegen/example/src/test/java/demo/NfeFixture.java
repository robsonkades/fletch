package demo;

import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;

/** Fixture construction and independent DOM oracle; never called by timed operations. */
final class NfeFixture {
    static final String RESOURCE = "/fixtures/invoice/35240612345678000195550010000274361328488326-procNFe.xml";
    private NfeFixture() {}

    static byte[] load(final int items) throws IOException {
        if (items < 1) throw new IllegalArgumentException("items must be positive");
        final byte[] base;
        try (var input = NfeFixture.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("NF-e fixture missing from classpath");
            base = input.readAllBytes();
        }
        if (items == 1) return base;
        final String xml = new String(base, StandardCharsets.UTF_8);
        final int start = xml.indexOf("<det ");
        final int close = xml.indexOf("</det>");
        if (start < 0 || close < start) throw new IllegalStateException("NF-e fixture has no item");
        final int end = close + "</det>".length();
        return (xml.substring(0, start) + xml.substring(start, end).repeat(items) + xml.substring(end))
                .getBytes(StandardCharsets.UTF_8);
    }

    static NfeDefinition.Nfe expected(final byte[] bytes) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        final Element inf = child(child(root, "NFe"), "infNFe");
        final Element ide = child(inf, "ide"), emit = child(inf, "emit"), dest = child(inf, "dest");
        final Element protocol = child(child(root, "protNFe"), "infProt");
        final var items = new ArrayList<NfeDefinition.Item>();
        for (var node = inf.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item && item.getLocalName().equals("det")) {
                final Element product = child(item, "prod");
                items.add(new NfeDefinition.Item(Integer.valueOf(item.getAttribute("nItem")),
                        text(product, "cProd"), text(product, "xProd"), new BigDecimal(text(product, "vProd"))));
            }
        }
        return new NfeDefinition.Nfe(inf.getAttribute("Id"),
                new NfeDefinition.Ide(Long.valueOf(text(ide, "nNF")), OffsetDateTime.parse(text(ide, "dhEmi")).toInstant()),
                new NfeDefinition.Party(text(emit, "CNPJ"), text(emit, "xNome"), text(child(emit, "enderEmit"), "UF")),
                new NfeDefinition.Party(choice(dest), text(dest, "xNome"), text(child(dest, "enderDest"), "UF")),
                items, new BigDecimal(text(child(child(inf, "total"), "ICMSTot"), "vNF")),
                new NfeDefinition.Protocol(text(protocol, "chNFe"), Integer.valueOf(text(protocol, "cStat")), text(protocol, "xMotivo")));
    }

    private static String choice(final Element parent) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && switch (element.getLocalName()) {
                case "CPF", "CNPJ", "idEstrangeiro" -> true;
                default -> false;
            }) return element.getTextContent();
        }
        return null;
    }

    private static Element child(final Element parent, final String name) {
        if (parent == null) return null;
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getLocalName().equals(name)) return element;
        }
        return null;
    }

    private static String text(final Element parent, final String name) {
        final Element found = child(parent, name);
        return found == null ? null : found.getTextContent();
    }
}
