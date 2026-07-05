# Fletch

[![Build](https://github.com/robsonkades/fletch/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/fletch/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/fletch)](https://central.sonatype.com/artifact/io.github.robsonkades/fletch)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](pom.xml)

**Fast, declarative XML extraction for Java.** Fletch reads a document in a single forward
pass over a streaming [Woodstox](https://github.com/FasterXML/woodstox)/StAX parser and
materializes exactly the values you ask for — no DOM tree, no reflection, no annotations,
no code generation.

```java
record Book(String title, Integer year) {}

Book book = Xml.extract(xml, doc -> doc.child("book", b -> new Book(
        b.value("title", String.class),
        b.value("year", Integer.class))));
```

## Why Fletch?

- **One pass, zero tree.** The document is never materialized. Memory usage is flat
  regardless of document size — only the values you extract are allocated.
- **Declarative, composable extractors.** An `XmlExtractor<T>` is a lambda that maps one
  element to one value. Extractors nest and compose like ordinary functions, and they are
  stateless constants you can share across threads.
- **Typed out of the box.** `String`, `Integer`, `Long`, `BigDecimal`, `Double`,
  `Boolean`, `Instant` and enums — converted directly from element text or attributes.
- **Fails fast in development.** In debug mode (`-ea`), reading elements against document
  order throws a descriptive exception instead of silently returning `null`. The check
  compiles away to nothing in production.
- **Secure by default.** DTDs and external entities are disabled — there is no XXE
  attack surface.
- **One exception type.** Every failure mode surfaces as the unchecked `XmlException`,
  with the underlying StAX error preserved as the cause.

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

Requires Java 17 or later. The only runtime dependency is `woodstox-core`.

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

## API at a glance

Everything happens through four public types:

| Type | Role |
|---|---|
| `Xml` | Entry points: `extract(String \| byte[] \| InputStream, extractor)` |
| `XmlExtractor<T>` | A lambda mapping one element to a typed value |
| `XmlCursor` | The navigation surface handed to extractors |
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

## The rules of the road

Fletch is a *forward-only* streaming reader. That buys its speed and flat memory profile,
and it imposes three rules:

1. **Read in document order.** Elements cannot be re-read. Requesting an element the
   cursor already passed reports absence (`null` / empty list) — or, in debug mode,
   throws an `XmlException` naming the misordered element.
2. **Read attributes before children.** Attributes belong to the element's start tag;
   any child navigation moves past it.
3. **A miss ends the scope.** Scanning for an element that isn't there consumes the rest
   of the enclosing element, so every later request in the same extractor also reports
   absence. Optional *trailing* elements therefore behave naturally; optional elements
   *in the middle* of a scope cost you everything after them on a miss — order your
   reads accordingly. `children(...)` and `skip()` also consume the scope.

The cursor never leaks into the parent scope: after an extractor returns, Fletch drains
whatever it left unread and continues cleanly at the next sibling.

### Debug mode: catch misordered extractors early

Run with assertions (`-ea`) — the Surefire default, so your tests get it automatically —
or with `-Dfletch.xml.debug=true`:

```
XmlException: Out-of-order read: element 'title' exists in this scope, but the cursor
already consumed it while serving an earlier request. Reorder the extractor calls to
follow document order.
```

The flag is read once at class load into a `static final`, so HotSpot eliminates every
debug branch in production — the check is literally free when off.

## Namespaces

Parsing is **not namespace-aware** (a deliberate performance choice — it skips 15–25 % of
per-element work). Elements are matched by their raw tag name:

- Documents with a **default namespace** (`<order xmlns="urn:...">`) match by plain
  local name: `child("order", ...)`. This covers the common profile of fiscal documents
  such as the Brazilian NF-e.
- **Prefixed** elements include the prefix in the name: `child("soap:Body", ...)`.

## Security

The shared parser factory is hardened by default:

- `SUPPORT_DTD = false` — DOCTYPE declarations are rejected;
- `IS_SUPPORTING_EXTERNAL_ENTITIES = false` — no external entity resolution (no XXE);
- bounded text length (1 MB) and element depth (200) guard against pathological inputs.

## Thread safety

`XmlExtractor` constants are stateless and safe to share across threads. Each
`Xml.extract(...)` call creates its own cursor; the underlying Woodstox factory is a
thread-safe singleton whose shared symbol table makes repeated parses of same-shaped
documents faster after warm-up.

## Performance notes

Fletch is designed for high-throughput extraction of small-to-medium documents
(tens to hundreds of KB):

- single forward pass, no DOM, no reflection;
- lazy parsing with location tracking disabled;
- single-chunk fast path for element text (no `StringBuilder` unless text is split);
- monomorphic cursor call sites that HotSpot inlines after warm-up;
- prefer the `byte[]` overload when the document is already in memory — it bootstraps
  Woodstox directly on the array with no stream indirection.

## Contributing

Bug reports, feature requests and pull requests are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md). Run `mvn verify` before submitting.

## License

Distributed under the [Apache License, Version 2.0](LICENSE).
