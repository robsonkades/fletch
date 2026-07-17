# Fletch

[![Build](https://github.com/robsonkades/fletch/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/fletch/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/fletch)](https://central.sonatype.com/artifact/io.github.robsonkades/fletch)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](pom.xml)

**Fast, declarative XML extraction for Java.** Fletch reads a document in a single forward
pass of its own byte-level scanning engine and materializes exactly the values you ask
for — no DOM tree, no reflection, no annotations, no code generation, no dependencies.

```java
record Book(String title, Integer year) {}

Book book = Xml.extract(xml, doc -> doc.child("book", b -> new Book(
        b.value("title", String.class),
        b.value("year", Integer.class))));
```

## Why Fletch?

- **One pass, minimal buffering.** The document is never materialized into a tree. Reads
  that follow document order allocate only the values you extract; a read that revisits an
  earlier sibling buffers just the current element's skipped children.
- **Declarative, composable extractors.** An `XmlExtractor<T>` is a lambda that maps one
  element to one value. Extractors nest and compose like ordinary functions, and they are
  stateless constants you can share across threads.
- **Typed out of the box.** `String`, `Integer`, `Long`, `BigDecimal`, `Double`,
  `Boolean`, `Instant` and enums — converted directly from element text or attributes.
- **Order-tolerant.** Read an element's fields in whatever order suits your record — the
  cursor serves them regardless of the order they appear in the XML, buffering only what
  it must revisit.
- **Secure by default.** DTDs and external entities are disabled — there is no XXE
  attack surface.
- **One exception type.** Every failure mode surfaces as the unchecked `XmlException`,
  carrying the byte offset of the problem in the source document.

## Installation

**Maven**

```xml
<dependency>
    <groupId>io.github.robsonkades</groupId>
    <artifactId>fletch</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle**

```kotlin
implementation("io.github.robsonkades:fletch:1.0.0")
```

Requires Java 17 or later. Fletch has **zero runtime dependencies**.

## Quick start

Given this document:

```xml
<order id="1042" urgent="true">
    <customer>
        <name>Ana Souza</name>
        <address>
            <city>Curitiba</city>
            <zip>80000-000</zip>
        </address>
    </customer>
    <item><sku>AB-1</sku><qty>2</qty><price>49.90</price></item>
    <item><sku>CD-2</sku><qty>1</qty><price>120.00</price></item>
    <total>219.80</total>
</order>
```

Define one extractor per element shape and compose them:

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlExtractor;

record Address(String city, String zip) {}
record Customer(String name, Address address) {}
record Item(String sku, Integer qty, BigDecimal price) {}
record Order(Long id, boolean urgent, Customer customer, List<Item> items, BigDecimal total) {}

class OrderExtractors {

    static final XmlExtractor<Address> ADDRESS = a -> new Address(
            a.value("city", String.class),
            a.value("zip", String.class));

    static final XmlExtractor<Customer> CUSTOMER = c -> new Customer(
            c.value("name", String.class),
            c.child("address", ADDRESS));

    static final XmlExtractor<Item> ITEM = i -> new Item(
            i.value("sku", String.class),
            i.value("qty", Integer.class),
            i.value("price", BigDecimal.class));

    static final XmlExtractor<Order> ORDER = o -> new Order(
            o.attribute("id", Long.class),          // attributes first — see rules below
            Boolean.TRUE.equals(o.attribute("urgent", Boolean.class)),
            o.child("customer", CUSTOMER),
            o.children("item", ITEM),
            o.value("total", BigDecimal.class));
}

// From a String, byte[] or InputStream — the cursor starts before the root element:
Order order = Xml.extract(inputStream, doc -> doc.child("order", OrderExtractors.ORDER));
```

## The engine underneath

Fletch 2 replaced the StAX parser with a goal-directed byte-level engine: tokenizer,
name matching and value decoding run in one fused loop over the document bytes, with
no per-event objects. Subtrees your extractor never asks about are crossed by a
balance-counting skip at SWAR scan speed, and — because the document is buffered —
a sibling scanned past on the way to a later request is remembered as a **byte span**
(four `int`s) and re-scanned only if you ask for it. Reading fields in document order
buffers nothing; reading them in any other order costs a cheap re-scan instead of the
event materialization it cost in 1.x. When your root extractor returns, reading stops:
trailing content is never scanned.

Measured on the bundled 7 KB NF-e fixture (i7-13700K, JDK 25), extraction runs
2.6–3.4× faster than Fletch 1.x with 4–8× less allocation, and about 2× faster than a
bare Woodstox event loop over the same document — while keeping the exact same API.
Reproduce with `mvn -P benchmarks package -DskipTests -Dgpg.skip=true` and
`java -jar target/benchmarks.jar ExtractionBenchmark`.

Scope notes: the engine reads UTF-8 and US-ASCII natively (ISO-8859-1 and UTF-16 are
transcoded once; other encodings are rejected), a single text value is capped at
16 MiB, and subtrees you never read are checked for tag balance only (mappings can opt
into full checks with `strictSkip()`). The design rationale lives in
[docs/extraction-engine-proposal.md](docs/extraction-engine-proposal.md).

Memory: exactly one path avoids holding the whole document — **a mapping over a UTF-8
(or US-ASCII) `InputStream`**, which slides a 64 KB window and holds the largest single
token instead (the window grows for tokens bigger than itself, up to the 16 MiB cap).
Everything else keeps the document in memory:

- the **cursor** drains an `InputStream` fully — order tolerance is what buys it, since
  a span the cursor may revisit has to still be there;
- **ISO-8859-1 and UTF-16** streams are read fully and transcoded before scanning, in
  both styles, because that scan is not incremental;
- `byte[]` and `String` are in memory by definition — Fletch just does not copy the
  `byte[]` (a `String` is encoded to UTF-8 once).

So for documents that dwarf memory: use a mapping, and feed it UTF-8.

## API at a glance

The `Xml` facade offers two extraction styles over the same engine — a pull-style
cursor and a push-style mapping:

| Type | Role |
|---|---|
| `Xml` | Entry points: `extract(String \| byte[] \| InputStream, extractor \| mapping)`, `mapping(...)` |
| `XmlExtractor<T>` | A lambda mapping one element to a typed value (cursor style) |
| `XmlCursor` | The navigation surface handed to extractors |
| `XmlMapping<T>` | A compiled, declarative mapping binding paths into a draft (mapping style) |
| `XmlBinding<D>` / `XmlValue` | A per-path binding and the lazily-decoded value it receives |
| `XmlException` | The single unchecked exception for all failures |

The cursor offers six operations:

| Method | Purpose |
|---|---|
| `child(name, extractor)` | Extract the first direct child with that name; `null` if absent |
| `children(name, extractor)` | Collect **all** direct children with that name into a mutable `List` |
| `value(name, type)` | Read a child's text converted to `type`; `null` if absent or empty |
| `firstOf(type, names...)` | Read whichever of several alternative elements is present (`xsd:choice`) |
| `attribute(name, type)` | Read an attribute of the current element; `null` if absent or empty |
| `skip()` | Discard the current element and its whole subtree |

### Supported value types

| Type | Format |
|---|---|
| `String` | as-is, surrounding whitespace trimmed |
| `Integer`, `Long`, `Double`, `BigDecimal` | standard Java number syntax |
| `Boolean` | `true` / `false` (case-insensitive), `1` / `0` |
| `Instant` | ISO-8601, e.g. `2026-01-15T10:30:00Z` |
| any `enum` | matched by constant name |

Absent elements, empty text and empty attributes uniformly convert to `null` — including
for `String`.

### Declarative mappings

For the same result without writing navigation, declare the wanted values up front as
paths and let the engine fill a mutable draft in one order-independent pass. Compile the
mapping once and reuse it — it is immutable and thread-safe. The order document from the
quick start, in mapping style:

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;

class OrderMapping {

    // records are immutable, so the mapping accumulates into mutable drafts
    static final class ItemDraft { String sku; Integer qty; BigDecimal price; }
    static final class Draft {
        Long id; boolean urgent;
        String name, city, zip;
        final List<Item> items = new ArrayList<>();
        BigDecimal total;
    }

    static final XmlMapping<Order> ORDER = Xml.mapping(Draft::new)
            .attr("/order@id",     (d, v) -> d.id     = v.asLong())
            .attr("/order@urgent", (d, v) -> d.urgent = v.asBoolean())
            .text("/order/customer/name",         (d, v) -> d.name = v.asString())
            .text("/order/customer/address/city", (d, v) -> d.city = v.asString())
            .text("/order/customer/address/zip",  (d, v) -> d.zip  = v.asString())
            .group("/order/item", ItemDraft::new,
                   (d, i) -> d.items.add(new Item(i.sku, i.qty, i.price)))
                .text("sku",   (i, v) -> i.sku   = v.asString())   // group-relative paths
                .text("qty",   (i, v) -> i.qty   = v.asInt())
                .text("price", (i, v) -> i.price = v.asDecimal())
                .endGroup()
            .text("/order/total", (d, v) -> d.total = v.asDecimal())
            .build(d -> new Order(d.id, d.urgent,
                    new Customer(d.name, new Address(d.city, d.zip)),
                    d.items, d.total));
}

Order order = Xml.extract(inputStream, OrderMapping.ORDER); // also String / byte[]
```

Paths are chains of raw tag names (`/order/customer/name`); attributes use `@`
(`/order@id`). `group(...)` declares repeating elements, `firstOf(...)` binds an
`xsd:choice`, and `required(...)` stops the scan once every required value is bound.
`strictSkip()` trades throughput to also verify end tags inside the subtrees no
declared path selects, which are otherwise only counted.

## How reads work

Fletch is a single streaming pass with a lazy per-scope buffer, so extractor calls are
**order-independent**:

1. **Read fields in any order.** A read that matches the next child in the stream is
   served directly; a read that targets a child appearing later buffers the siblings
   scanned past, so a later request for one of them is still answered. Reads that follow
   document order buffer nothing — that is the fast, flat-memory path.
2. **Attributes any time.** Attributes are snapshotted when the cursor enters an element,
   so `attribute(...)` works before or after navigating to children.
3. **Misses are cheap and local.** Requesting an element that isn't there yields `null`
   (or an empty list) in any position; it never poisons later reads. `skip()` discards
   the rest of the current element — after it, every request reports absence.

`children(...)` returns its matches in document order. The cursor never leaks into the
parent scope: after an extractor returns, Fletch drains whatever it left unread and
continues cleanly at the next sibling.

## Namespaces

Parsing is **not namespace-aware** (a deliberate performance choice — it skips 15–25 % of
per-element work). Elements are matched by their raw tag name:

- Documents with a **default namespace** (`<order xmlns="urn:...">`) match by plain
  local name: `child("order", ...)`. This covers the common profile of fiscal documents
  such as the Brazilian NF-e.
- **Prefixed** elements include the prefix in the name: `child("soap:Body", ...)`.

## Security

The engine is hardened by default:

- `<!DOCTYPE` is rejected at its first byte — no DTD processing, no XXE surface;
- only the five predefined entities and numeric character references are decoded;
- a single text value is capped at 16 MiB to guard against pathological inputs.

## Thread safety

`XmlExtractor` constants are stateless and safe to share across threads. Each
`Xml.extract(...)` call runs on its own engine drawn from a small internal pool, so
concurrent extractions never share mutable state and steady-state calls reuse the
scanning buffers instead of reallocating them.

## Performance notes

Fletch is designed for high-throughput extraction of small-to-medium documents
(tens to hundreds of KB):

- single forward pass, no DOM, no reflection, no per-event objects;
- unqueried subtrees crossed at SWAR scan speed, tag names never materialized;
- extraction stops as soon as your root extractor returns;
- single-span fast path for element text (entities and CDATA take a cooked path
  only when present);
- prefer the `byte[]` overload when the document is already in memory — it is
  scanned in place with zero copying.

## Contributing

Bug reports, feature requests and pull requests are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md). Run `mvn verify -Dgpg.skip=true` before
submitting (artifact signing runs only in the release workflow).

## License

Distributed under the [Apache License, Version 2.0](LICENSE).
