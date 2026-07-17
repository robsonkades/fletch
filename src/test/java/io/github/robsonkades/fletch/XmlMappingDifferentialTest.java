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

import com.ctc.wstx.stax.WstxInputFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.io.Stax2ByteArraySource;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential fuzzing of the plan engine against Woodstox as the oracle.
 *
 * <p>A seeded generator produces random well-formed documents exercising
 * entities, character references, CDATA, comments, PIs, prefixed names,
 * carriage returns, quote variants, dirty attribute values and Latin-1
 * encoding. Every path that is a pure leaf across the whole document (plus
 * every attribute) is bound in an {@link XmlMapping}; the extracted map must
 * equal a reference map computed by an independent Woodstox event walk that
 * applies the cursor API's text-assembly rules (whitespace-only chunks
 * dropped, result stripped, empty means absent, last occurrence wins).
 *
 * <p>Generator invariants that keep the comparison meaningful: documents are
 * well-formed (rejection parity is covered by the fixture tests), text never
 * contains {@code ]]}, and comments never contain {@code --}.
 */
class XmlMappingDifferentialTest {

    private static final int DOCS = 400;
    private static final long SEED = 42;
    private static final int MAX_BOUND_PATHS = 60;

    private static final WstxInputFactory ORACLE;

    static {
        ORACLE = new WstxInputFactory();
        ORACLE.setProperty(XMLInputFactory2.SUPPORT_DTD, false);
        ORACLE.setProperty(XMLInputFactory2.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        ORACLE.setProperty(XMLInputFactory2.IS_COALESCING, false);
        ORACLE.setProperty(XMLInputFactory2.IS_NAMESPACE_AWARE, false);
    }

    @Test
    void randomDocumentsExtractIdenticallyToTheWoodstoxReference() throws Exception {
        final Random rnd = new Random(SEED);
        for (int doc = 0; doc < DOCS; doc++) {
            final boolean latin1 = doc % 5 == 3;
            final Elem root = genElem(rnd, 0, latin1, new int[] {0});
            final String xml = serialize(root, rnd, latin1, doc % 7 == 2 && !latin1);
            final byte[] bytes = latin1
                    ? xml.getBytes(StandardCharsets.ISO_8859_1)
                    : xml.getBytes(StandardCharsets.UTF_8);

            final Set<String> leafPaths = new TreeSet<>();
            final Set<String> attrPaths = new TreeSet<>();
            classify(root, "", new HashMap<>(), leafPaths, attrPaths);
            trim(leafPaths, attrPaths);

            final Map<String, String> expected = reference(bytes, leafPaths, attrPaths);
            final XmlMapping<Map<String, String>> plan = buildPlan(leafPaths, attrPaths);

            final int docId = doc;
            assertEquals(new TreeMap<>(expected), new TreeMap<>(plan.extract(bytes)),
                    () -> "divergence on generated doc #" + docId + " (seed " + SEED + "):\n" + xml);

            // The same document dripped through a tiny sliding window must
            // extract identically — every token boundary becomes a refill.
            final Map<String, String> streamed = new XmlMappingEngine<>(plan, 64)
                    .extract(new XmlMappingTest.Drip(bytes, SEED ^ doc));
            assertEquals(new TreeMap<>(expected), new TreeMap<>(streamed),
                    () -> "streaming divergence on generated doc #" + docId + " (seed " + SEED + "):\n" + xml);
        }
    }

    // ------------------------------------------------------------------ engine under test

    private static XmlMapping<Map<String, String>> buildPlan(final Set<String> leafPaths, final Set<String> attrPaths) {
        final XmlMapping.Builder<Map<String, String>> builder = XmlMapping.builder(HashMap::new);
        for (final String path : leafPaths) {
            builder.text(path, (m, v) -> m.put(path, v.asString()));
        }
        for (final String path : attrPaths) {
            builder.attr(path, (m, v) -> m.put(path, v.asString()));
        }
        return builder.build(m -> m);
    }

    // ------------------------------------------------------------------ Woodstox reference walk

    private static Map<String, String> reference(final byte[] doc, final Set<String> leafPaths,
                                                  final Set<String> attrPaths) throws XMLStreamException {
        final Map<String, String> out = new HashMap<>();
        final XMLStreamReader r = ORACLE.createXMLStreamReader(new Stax2ByteArraySource(doc, 0, doc.length));
        final List<StringBuilder> text = new ArrayList<>();
        String path = "";
        int depth = 0;
        while (r.hasNext()) {
            final int e = r.next();
            if (e == XMLStreamConstants.START_ELEMENT) {
                path = path + "/" + r.getLocalName();
                depth++;
                if (text.size() < depth) text.add(new StringBuilder());
                text.get(depth - 1).setLength(0);
                for (int i = 0; i < r.getAttributeCount(); i++) {
                    final String key = path + "@" + r.getAttributeLocalName(i);
                    if (attrPaths.contains(key) && !r.getAttributeValue(i).isEmpty()) {
                        out.put(key, r.getAttributeValue(i));
                    }
                }
            } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
                if (depth > 0 && !r.isWhiteSpace()) {
                    text.get(depth - 1).append(r.getText());
                }
            } else if (e == XMLStreamConstants.END_ELEMENT) {
                if (leafPaths.contains(path)) {
                    final String value = text.get(depth - 1).toString().strip();
                    if (!value.isEmpty()) out.put(path, value);
                }
                depth--;
                path = path.substring(0, path.lastIndexOf('/'));
            }
        }
        r.close();
        return out;
    }

    // ------------------------------------------------------------------ document model + generator

    private static final class Elem {
        final String name;
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<Object> kids = new ArrayList<>();

        Elem(final String name) {
            this.name = name;
        }
    }

    private record Text(String s) {}

    private record Cdata(String s) {}

    private record Comment(String s) {}

    private static final String[] NAMES = {
            "a", "b", "item", "data", "x", "name", "id", "ns:t", "v1", "total"
    };

    private static Elem genElem(final Random rnd, final int depth, final boolean latin1, final int[] budget) {
        final Elem e = new Elem(NAMES[rnd.nextInt(NAMES.length)]);
        budget[0]++;
        final int attrs = rnd.nextInt(3);
        for (int i = 0; i < attrs; i++) {
            e.attrs.putIfAbsent(NAMES[rnd.nextInt(NAMES.length)].replace(':', '_'),
                    rnd.nextInt(10) == 0 ? "" : genText(rnd, latin1, true));
        }
        final int kids = depth == 0 ? 1 + rnd.nextInt(4) : rnd.nextInt(5);
        for (int i = 0; i < kids; i++) {
            final int kind = rnd.nextInt(100);
            if (kind < 50 && depth < 5 && budget[0] < 150) {
                e.kids.add(genElem(rnd, depth + 1, latin1, budget));
            } else if (kind < 80) {
                e.kids.add(new Text(genText(rnd, latin1, false)));
            } else if (kind < 88) {
                e.kids.add(new Cdata(genCdata(rnd)));
            } else if (kind < 95) {
                e.kids.add(new Comment("c" + rnd.nextInt(100)));
            } else {
                e.kids.add("pi");
            }
        }
        return e;
    }

    private static String genText(final Random rnd, final boolean latin1, final boolean attr) {
        final String ascii = "abc XYZ 09 <>&\"' \t";
        final String extra = latin1 ? "éãç" : "éãç世—";
        final StringBuilder sb = new StringBuilder();
        final int len = 1 + rnd.nextInt(24);
        for (int i = 0; i < len; i++) {
            final int pick = rnd.nextInt(100);
            if (pick < 82) {
                sb.append(ascii.charAt(rnd.nextInt(ascii.length())));
            } else if (pick < 90) {
                sb.append(extra.charAt(rnd.nextInt(extra.length())));
            } else if (pick < 95 && !attr) {
                sb.append(rnd.nextBoolean() ? "\r\n" : "\r");
            } else {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String genCdata(final Random rnd) {
        final String pool = "cdata <>&\"' body \n";
        final StringBuilder sb = new StringBuilder();
        final int len = rnd.nextInt(16);
        for (int i = 0; i < len; i++) {
            sb.append(pool.charAt(rnd.nextInt(pool.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ serialization with escaping variants

    private static String serialize(final Elem root, final Random rnd, final boolean latin1, final boolean bom) {
        final StringBuilder sb = new StringBuilder();
        if (bom) sb.append('﻿');
        if (latin1) {
            sb.append("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
        } else if (rnd.nextBoolean()) {
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        }
        if (rnd.nextInt(4) == 0) sb.append("<!-- prolog -->\n");
        writeElem(root, sb, rnd);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeElem(final Elem e, final StringBuilder sb, final Random rnd) {
        sb.append('<').append(e.name);
        for (final Map.Entry<String, String> a : e.attrs.entrySet()) {
            final char q = rnd.nextBoolean() ? '"' : '\'';
            sb.append(' ').append(a.getKey()).append('=').append(q);
            escapeAttr(a.getValue(), q, sb, rnd);
            sb.append(q);
        }
        if (e.kids.isEmpty() && rnd.nextBoolean()) {
            sb.append("/>");
            return;
        }
        sb.append('>');
        for (final Object kid : e.kids) {
            if (kid instanceof Elem child) {
                writeElem(child, sb, rnd);
            } else if (kid instanceof Text t) {
                escapeText(t.s(), sb, rnd);
            } else if (kid instanceof Cdata c) {
                sb.append("<![CDATA[").append(c.s()).append("]]>");
            } else if (kid instanceof Comment c) {
                sb.append("<!--").append(c.s()).append("-->");
            } else {
                sb.append("<?pi data?>");
            }
        }
        sb.append("</").append(e.name).append('>');
    }

    private static void escapeText(final String raw, final StringBuilder sb, final Random rnd) {
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append(rnd.nextBoolean() ? "&gt;" : ">");
            } else if (rnd.nextInt(40) == 0) {
                sb.append(rnd.nextBoolean() ? "&#" + (int) c + ";" : "&#x" + Integer.toHexString(c) + ";");
            } else {
                sb.append(c);
            }
        }
    }

    private static void escapeAttr(final String raw, final char quote, final StringBuilder sb, final Random rnd) {
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == quote) {
                sb.append(quote == '"' ? "&quot;" : "&apos;");
            } else if (rnd.nextInt(30) == 0) {
                sb.append("&#").append((int) c).append(';');
            } else {
                sb.append(c);
            }
        }
    }

    // ------------------------------------------------------------------ path classification

    /**
     * Collects every attribute path and every element path that is a pure
     * leaf (has no element children in any of its occurrences) — the paths a
     * plan may legally bind as text targets.
     */
    private static void classify(final Elem e, final String parent, final Map<String, Boolean> hasElemChild,
                                 final Set<String> leafPaths, final Set<String> attrPaths) {
        final String path = parent + "/" + e.name;
        boolean elemChild = false;
        for (final Object kid : e.kids) {
            if (kid instanceof Elem) {
                elemChild = true;
                break;
            }
        }
        hasElemChild.merge(path, elemChild, Boolean::logicalOr);
        if (hasElemChild.get(path)) {
            leafPaths.remove(path);
        } else {
            leafPaths.add(path);
        }
        for (final String attr : e.attrs.keySet()) {
            attrPaths.add(path + "@" + attr);
        }
        for (final Object kid : e.kids) {
            if (kid instanceof Elem child) {
                classify(child, path, hasElemChild, leafPaths, attrPaths);
            }
        }
    }

    /** Keeps the combined bound-path count under the plan's 64-field cap. */
    private static void trim(final Set<String> leafPaths, final Set<String> attrPaths) {
        while (leafPaths.size() + attrPaths.size() > MAX_BOUND_PATHS) {
            if (!attrPaths.isEmpty()) {
                attrPaths.remove(attrPaths.iterator().next());
            } else {
                leafPaths.remove(leafPaths.iterator().next());
            }
        }
    }
}
