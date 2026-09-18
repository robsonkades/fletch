# Fletch

[![Build](https://github.com/robsonkades/fletch/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/fletch/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/fletch)](https://central.sonatype.com/artifact/io.github.robsonkades/fletch)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](pom.xml)

**Fast, declarative XML extraction for Java.** Fletch reads a document in a single forward
pass of its own byte-level scanning engine and materializes exactly the values you ask
for, using a runtime with no DOM tree, reflection, annotations or external dependencies.
The DSL works directly; optional [build-time codegen](codegen/README.md) can specialize
element and attribute lookup for a fixed mapping.

```java
record Book(String title, Integer year) {}

Book book = Xml.extract(xml, doc -> doc.child("book", b -> new Book(
        b.value("title", String.class),
        b.value("year", Integer.class))));
```

## Why Fletch?

- **Minimal buffering.** The document is never materialized into a tree. The cursor
  records skipped siblings as byte spans for later reads; a compiled mapping can
  discard subtrees that contain no selected fields.
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
- **Explicit errors.** Parse failures use `XmlException` with a byte offset; I/O
  failures preserve their cause. Conversion errors retain their Java exception type,
  such as `NumberFormatException` or `DateTimeParseException`.

## Installation

**Maven**

```xml
<dependency>
    <groupId>io.github.robsonkades</groupId>
    <artifactId>fletch</artifactId>
    <version>1.3.0</version>
</dependency>
```

**Gradle**

```kotlin
implementation("io.github.robsonkades:fletch:1.3.0")
```

Requires Java 17 or later. Fletch has **zero runtime dependencies**.

For an upgrade from 1.2.0, read the [compatibility notes](docs/releases/1.3.0.md#upgrading-from-120),
especially the stricter validation and resource ceilings.

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

Fletch uses a goal-directed byte-level engine: tokenizer,
name matching and value decoding run in one fused loop over the document bytes, with
no per-event objects. Subtrees your extractor never asks about are crossed by a
balance-counting skip at SWAR scan speed, and — because the document is buffered —
a sibling scanned past on the way to a later request is remembered as a **byte span**
(four `int`s) and re-scanned only if you ask for it. Reading fields in document order
buffers nothing; reading them in any other order costs a cheap re-scan instead of the
event materialization it cost in 1.x. When your root extractor returns, reading stops:
trailing content is never scanned.

Measure the bundled NF-e fixture with
`mvn -P benchmarks package -DskipTests -Dgpg.skip=true` and
`java -jar target/benchmarks.jar ExtractionBenchmark -prof gc`.
The benchmark compares cursor and mapping extraction; its bare Woodstox event loop
is a scanning reference and does not construct the same result object.
`CorpusBenchmark` adds rotating documents with Unicode, reordered fields,
attributes and ignored subtrees, across input forms and resource limits. See
[the benchmark guide](docs/benchmarking.md) for bounded runs and paired comparisons.

Scope notes: the engine reads UTF-8 and US-ASCII natively (ISO-8859-1 and UTF-16 are
transcoded once; other encodings are rejected). Selected text is capped at 16 MiB
of UTF-8 content bytes before entity decoding and trimming, summed across text and
CDATA runs of a value. Attributes have the same byte limit; streaming start tags
and anchored CDATA sections must also fit the 16 MiB token window. Selected values
are checked for valid UTF-8 and XML 1.0 characters. Mappings can use `strictSkip()`
to compare complete end-tag names in skipped subtrees. Extraction stops early and
does not certify the well-formedness of the entire document.

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
| `XmlLimits` | Immutable limits passed per extraction, shared safely across threads |
| `XmlBinding<D>` / `XmlValue` | A per-path binding and the lazily-decoded value it receives |
| `XmlException` | Parse, resource-limit and I/O failures; conversion exceptions retain their Java type |

The cursor offers these operations:

| Method | Purpose |
|---|---|
| `exists(name)` | Check for a remaining direct child, including empty tags, preserving it for a later read |
| `child(name, extractor)` | Extract the first direct child with that name; `null` if absent |
| `children(name, extractor)` | Collect **all** direct children with that name into a mutable `List` |
| `value(name, type)` | Read a child's text converted to `type`; `null` if absent or empty |
| `firstOf(type, names...)` | Read whichever of several alternative elements is present (`xsd:choice`) |
| `attribute(name, type)` | Read an attribute of the current element; `null` if absent or empty |
| `valueWith(name, converter)` | Read a child's text using your conversion function |
| `valueWith(name, converter, fallbackSupplier)` | Convert available text or lazily compute a fallback for absent/empty text |
| `firstOfWith(converter, names...)` | Convert the first matching alternative in document order |
| `attributeWith(name, converter)` | Read an attribute using your conversion function |
| `attributeWith(name, converter, fallbackSupplier)` | Convert an attribute or lazily compute a fallback when absent/empty |
| `skip()` | Discard the current element and its whole subtree |

### Supported value types

| Type | Format |
|---|---|
| `String` | decoded text; element text is trimmed, attributes are not trimmed |
| `Byte`, `Short`, `Integer`, `Long`, `BigInteger` | signed decimal integers; bounded types reject overflow |
| `Float`, `Double`, `BigDecimal` | Java number syntax; floating-point types also accept NaN and infinities |
| `Boolean` | `true` / `false` (case-insensitive), `1` / `0` |
| `Character` | one non-surrogate UTF-16 character; supplementary characters require `String` |
| `Instant` | ISO-8601, e.g. `2026-01-15T10:30:00Z` |
| `LocalDate`, `LocalTime`, `LocalDateTime` | ISO local date/time, without a timezone |
| `OffsetTime`, `OffsetDateTime`, `ZonedDateTime` | ISO time/date-time with an offset or zone, following the JDK parser |
| `Duration`, `Period` | ISO duration (`PT2H30M`) or calendar period (`P1Y2M`) |
| `UUID` | `UUID.fromString`, e.g. `123e4567-e89b-12d3-a456-426614174000` |
| any `enum` | matched by constant name |

Absent elements, empty text and empty attributes uniformly convert to `null` — including
for `String`.

Primitive class tokens (`int.class`, `boolean.class`, etc.) are aliases for their
wrapper classes. Results still can be `null`; unboxing an absent value throws.
Mappings can request any built-in type with `value.as(LocalDate.class)` while
retaining the existing `asInt()`, `asDecimal()` and other specialized accessors.

For a custom format or a domain type, pass a reusable `Function`:

```java
DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
Function<String, LocalDate> readDate = text -> LocalDate.parse(text, dateFormat);

LocalDate date = cursor.valueWith("date", readDate);
LocalDate effectiveDate = cursor.valueWith("effectiveDate", readDate, () -> defaultDate);
// The same function in a mapping:
XmlMapping<LocalDate> mapping = Xml.mapping(() -> new LocalDate[1])
        .text("/order/date", (draft, value) -> draft[0] = value.convert(readDate))
        .build(draft -> draft[0]);
```

Converters receive decoded text, run only for present, non-empty values, and may
return `null`. Their exceptions propagate unchanged. See the
[conversion guide](docs/value-conversions.md) for complete examples and contracts.
The fallback supplier runs only when the source text is absent or empty; a null
converter result or a conversion error does not trigger it. `exists("date")`
checks tag presence, so an empty tag exists even though its text uses the fallback.
The check preserves the occurrence for a later read. These additions are
unreleased; Maven Central 1.3.0 contains the previous API.

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

### Optional build-time codegen (experimental)

The [codegen prototype](codegen/README.md) generates Java 17 lookup code from a public
factory using the existing mapping DSL. Generated mappings retain the same extraction,
validation, limits and session contracts. Build the core, generator and executable
consumer with `mvn -f codegen/pom.xml verify -Dgpg.skip=true`.

### Reusable mapping sessions

For a batch of documents on one worker thread, open a session once and reuse
its dedicated engine:

```java
try (var session = OrderMapping.ORDER.openSession()) {
    for (byte[] document : documents) {
        Order order = session.extract(document); // also String / InputStream
        process(order);
    }
}
```

Use `openSession(limits)` to apply the same `XmlLimits` independently to every
document. Create, use and close each session on the same worker thread. Sessions
reject recursive extraction and closing from a callback. They remain usable after
parsing or callback failures, and never close input streams supplied by the caller.
Closing releases the engine and prevents further extraction.

Sessions avoid pool access per document; the benefit depends on the workload.
See the [session contract and worker example](docs/mapping-sessions.md).

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
- selected values reject malformed UTF-8 and forbidden XML characters;
- scanned start tags reject unquoted, duplicate and malformed attributes;
- each start tag is limited to 1,024 attributes, bounding duplicate-name checks;
- selected text and attributes have a 16 MiB content limit before decoding/trimming.

These checks apply to the content the extraction visits. Unselected text and trailing
content are not fully validated; `strictSkip()` extends end-tag checks inside skipped
subtrees, and does not turn extraction into whole-document XML validation.

## Resource limits and validation coverage

Use the same limits with either extraction style and any input form:

```java
XmlLimits limits = XmlLimits.builder()
        .maxInputBytes(8 * 1024 * 1024)
        .maxDepth(64)
        .maxElements(100_000)
        .maxNameBytes(256)
        .maxAttributesPerElement(32)
        .maxTextBytes(1024 * 1024)
        .build();

Order order = Xml.extract(bytes, limits, ORDER_MAPPING);
Order cursorOrder = Xml.extract(bytes, limits, doc -> doc.child("order", OrderExtractors.ORDER));
```

The numbers above are examples; choose limits for your document contract. Existing
two-argument calls use `XmlLimits.defaults()`, preserving the 16 MiB content and
1,024-attribute ceilings without adding input, depth, element-count or name limits.
Content and attribute limits can be lowered to zero; the other limits must be positive.

Depth and element counts include skipped subtrees and empty elements; a cursor
replay counts each element once. Names include prefixes and are measured in UTF-8
bytes. Content limits count raw UTF-8 text before entity decoding and trimming;
all text/CDATA runs within a selected value count together. Attributes in every
scanned start tag are checked, even when not selected.

The input limit counts original bytes, including BOMs. Arrays and strings are
checked in full; strings are measured as UTF-8 before allocating the encoded array.
Streams are limited as read, including read-ahead, and may consume one extra byte
to distinguish EOF at the limit from oversized input. A mapping that exits early
may leave the remainder unread and unchecked. Legacy encodings are counted before
transcoding; their UTF-8 representation and intermediate buffers can be larger.

Limit violations throw `XmlException`. Streams remain open, and earlier callbacks
may already have run. Limits cover parser work, not allocations made by callbacks.
They do not add whole-document XML validation. See the
[coverage table and resource contract](docs/resource-limits.md) for details.

## Thread safety

`XmlExtractor` constants are stateless and safe to share across threads. Each
`Xml.extract(...)` call runs on its own engine drawn from a small internal pool, so
concurrent extractions never share mutable state and steady-state calls reuse the
scanning buffers instead of reallocating them. Engines release caller-owned source
references after success or failure and discard scratch buffers larger than 1 MiB
before returning to the pool.

A mapping may also be shared across workers, provided its callbacks and draft
suppliers support concurrent use with separate drafts. Each worker must create
its own `XmlMappingSession`; a session rejects calls from other threads.

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
