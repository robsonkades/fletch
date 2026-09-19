# Fletch

[![Build](https://github.com/robsonkades/fletch/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/fletch/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/fletch)](https://central.sonatype.com/artifact/io.github.robsonkades/fletch)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](pom.xml)

**Declarative XML extraction for Java 17+, with no runtime dependencies.**
Fletch reads XML bytes and builds the values your application requests, without
constructing a DOM tree. Use a cursor to compose extractors, or declare a reusable
mapping of paths to callbacks. Both support attributes, repeated elements, typed
values and configurable resource limits.

This README is the project guide. It covers the current source tree; features
marked **unreleased** are not in the published `1.3.0` artifact.

## Contents

- [Installation and version availability](#installation-and-version-availability)
- [Quick start](#quick-start)
- [Choosing an extraction API](#choosing-an-extraction-api)
- [Cursor navigation](#cursor-navigation)
- [Value types and text handling](#value-types-and-text-handling)
- [Custom conversions, presence and fallbacks](#custom-conversions-presence-and-fallbacks)
- [Declarative mappings](#declarative-mappings)
- [Mapping values and defaults](#mapping-values-and-defaults)
- [Reusable sessions](#reusable-sessions)
- [Inputs, encodings and stream ownership](#inputs-encodings-and-stream-ownership)
- [Resource limits](#resource-limits)
- [Validation and errors](#validation-and-errors)
- [Concurrency and object lifetime](#concurrency-and-object-lifetime)
- [Optional code generation](#optional-code-generation)
- [Architecture and public API](#architecture-and-public-api)
- [Building and testing](#building-and-testing)
- [Performance and benchmarks](#performance-and-benchmarks)
- [Compatibility and upgrades](#compatibility-and-upgrades)
- [Troubleshooting](#troubleshooting)
- [Contributing and releases](#contributing-and-releases)
- [License](#license)

## Installation and version availability

The published core version documented here is **1.3.0**.

Maven:

```xml
<dependency>
    <groupId>io.github.robsonkades</groupId>
    <artifactId>fletch</artifactId>
    <version>1.3.0</version>
</dependency>
```

Gradle Kotlin DSL:

```kotlin
implementation("io.github.robsonkades:fletch:1.3.0")
```

The core needs Java 17 or later and only `java.base` at runtime. Its test and
benchmark dependencies are not application dependencies.

| Availability | Features |
|---|---|
| Published 1.3.0 | Cursor, mapping DSL, `XmlLimits`, mapping sessions; `String`, `Integer`, `Long`, `Double`, `BigDecimal`, `Boolean`, `Instant` and enums |
| Current source, unreleased | Additional [value types](#value-types-and-text-handling), primitive class aliases, custom converters, `exists()`, lazy fallbacks, `XmlValue.as(Class)` and `convert(Function)`, cursor `LocalDate` byte parsing |
| Optional, built from source | Experimental mapping code generator and its example module; the release workflow publishes only the core |

To try unreleased APIs, use a source build with a distinct local version, such as
`1.4.0-SNAPSHOT`. From the repository root, run these commands in Bash or PowerShell:

```sh
mvn -B -ntp -f codegen/pom.xml org.codehaus.mojo:versions-maven-plugin:2.22.0:set '-DprocessAllModules=true' '-DnewVersion=1.4.0-SNAPSHOT' '-DgenerateBackupPoms=false'
mvn -B -ntp -f codegen/pom.xml install '-Dgpg.skip=true'
```

This changes all four local POMs and installs that version into your local Maven
repository. Set your application's dependency to the same version. The example
version above does not identify a published release.

## Quick start

Save this as `CursorExample.java`. It uses APIs available in 1.3.0.

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlExtractor;
import java.math.BigDecimal;
import java.util.List;

public class CursorExample {
    record Item(String sku, Integer quantity) {}
    record Order(String id, List<Item> items, BigDecimal total) {}

    static final XmlExtractor<Item> ITEM = item -> new Item(
            item.value("sku", String.class),
            item.value("quantity", Integer.class));

    static final XmlExtractor<Order> ORDER = order -> new Order(
            order.attribute("id", String.class),
            order.children("item", ITEM),
            order.value("total", BigDecimal.class));

    public static void main(String[] args) {
        String xml = """
                <order id="A-42">
                  <total>12.30</total>
                  <item><quantity>2</quantity><sku>BOOK</sku></item>
                </order>
                """;
        Order order = Xml.extract(xml, doc -> doc.child("order", ORDER));
        System.out.println(order);
    }
}
```

Output:

```text
Order[id=A-42, items=[Item[sku=BOOK, quantity=2]], total=12.30]
```

The outer cursor starts **before the document root**. `doc.child("order", ORDER)`
enters that root. Inside `ORDER`, navigation addresses its direct children and
attributes. The example deliberately requests fields in a different order from
the XML; the cursor remembers siblings it passes and serves them later.

After building the core from this checkout, compile and run the example:

```sh
javac --release 17 -cp target/fletch-1.3.0.jar CursorExample.java
```

Bash on Linux/macOS:

```sh
java --limit-modules java.base -cp 'target/fletch-1.3.0.jar:.' CursorExample
```

PowerShell or Git Bash on Windows:

```sh
java --limit-modules java.base -cp 'target/fletch-1.3.0.jar;.' CursorExample
```

If you changed the local version, substitute it in the jar name.

## Choosing an extraction API

| Need | API | Behavior |
|---|---|---|
| Compose small extractors or make decisions while navigating | `XmlExtractor<T>` and `XmlCursor` | Reads requested children; remembers preceding siblings for later reads |
| Extract a known set of paths into a result | `XmlMapping<T>` | Compiles paths once; invokes bindings in document order; UTF-8 streams use a sliding window |
| Process a batch on one worker | `XmlMappingSession<T>` | Reuses a dedicated mapping engine on its creating thread |
| Experiment with specialized lookup for a fixed mapping | Optional codegen | Generates Java lookup code while keeping the mapping engine and its contracts |

`Xml.extract(input, extractor)` and `Xml.extract(input, mapping)` accept `byte[]`,
`String` or `InputStream`. Each also has an explicit-limits overload with argument
order `Xml.extract(input, limits, extractorOrMapping)`. Two-argument calls use
`XmlLimits.defaults()`.

Fletch is a selective XML reader. XML writing, object serialization, XPath, XSD
validation and parsing other formats are outside its API.

## Cursor navigation

Every name is a **raw XML name**. `child("address", ...)` looks for a direct child,
not an arbitrary descendant. Enter each container to reach deeper values.

| Method | Result and consumption |
|---|---|
| `name()` | Current element's raw tag name; `null` on the outer cursor before the root |
| `child(name, extractor)` | Extracts the next remaining matching child; `null` if absent |
| `children(name, extractor)` | Extracts all remaining matching children in document order; returns a mutable list, empty if absent |
| `value(name, type)` | Consumes the next matching child and converts its text; `null` if absent, empty or blank |
| `attribute(name, type)` | Reads an attribute of the current element; `null` if absent or empty; can be reread before or after child navigation |
| `firstOf(type, names...)` | Consumes the first remaining matching alternative in document order; argument order does not set priority |
| `exists(name)` — unreleased | Checks for a remaining matching child without consuming it; empty or blank elements count as present |
| `valueWith`, `attributeWith`, `firstOfWith` — unreleased | Apply application conversion functions; see [custom conversions](#custom-conversions-presence-and-fallbacks) |
| `skip()` | Discards remaining child content in the current scope; subsequent child reads report absence |

Each child occurrence can be consumed once. Reading the same name twice reads
two occurrences, rather than rereading the first. A failed lookup may scan to the
end of the current element, remembering other siblings so later requests still
work. Nothing leaks into the parent scope.

`firstOf` consumes its selected occurrence only. If that occurrence is blank, the
result is `null`; it does not seek another alternative to obtain a nonempty value.
Other occurrences remain available to later navigation. Mapping `firstOf` has a
different [nonempty-value rule](#declarative-mappings).

`exists()` can scan ahead and remember preceding siblings. It stops at a matching
start tag without decoding the value or checking that entire subtree. Repeating
it returns `true` until a read consumes that occurrence. Use it to distinguish
structural presence from absence; use a fallback converter when only usable text
matters.

Keep cursor operations inside their extractor invocation. A parent cursor is not
available while its child extractor is active. Retaining a cursor after its
callback returns, or using it on another thread, violates its scope.

## Value types and text handling

These targets work with cursor `value`, `attribute` and `firstOf`. In the current
source, mapping `XmlValue.as(type)` accepts the same targets. An asterisk marks a
type added after the published 1.3.0 release.

| Target | Accepted form or conversion rule |
|---|---|
| `String` | Decoded XML text |
| `Byte`*, `Short`*, `Integer`, `Long` | Signed decimal integers; values outside the target range fail |
| `BigInteger`* | Arbitrary-size integer through the JDK parser |
| `BigDecimal` | Exact decimal value, including exponent notation; preserves decimal scale |
| `Float`*, `Double` | JDK floating-point syntax and behavior, including NaN, infinity and overflow |
| `Boolean` | `true`/`false`, case-insensitive, or `1`/`0`; other values fail |
| `Character`* | Exactly one non-surrogate UTF-16 code unit; a supplementary character such as an emoji is not a `Character` |
| `UUID`* | `UUID.fromString` semantics |
| `Instant` | ISO instant, for example `2026-09-18T12:30:00Z` |
| `LocalDate`* | ISO date, for example `2026-09-18` |
| `LocalTime`* | ISO local time, for example `12:30:00` |
| `LocalDateTime`* | ISO local date/time, for example `2026-09-18T12:30:00` |
| `OffsetTime`* | ISO time with offset, for example `12:30:00-03:00` |
| `OffsetDateTime`* | ISO date/time with offset, for example `2026-09-18T12:30:00-03:00` |
| `ZonedDateTime`* | JDK zoned date/time form, for example `2026-09-18T12:30:00-03:00[America/Sao_Paulo]` |
| `Duration`*, `Period`* | ISO forms, for example `PT15M` and `P2D` |
| Any enum | Exact, case-sensitive constant name |

Temporal conversion follows the corresponding JDK parser; it does not supply an
application time zone or guess a localized format. Use a custom converter for
other formats or domain types. Unsupported target classes throw `XmlException`.

Primitive class tokens (`byte.class`, `short.class`, `int.class`, `long.class`,
`float.class`, `double.class`, `boolean.class`, `char.class`) are **unreleased**
aliases for their wrappers. Cursor results remain nullable. Unboxing an absent
result throws `NullPointerException`; use a wrapper or an explicit default.

| XML input | Text contract |
|---|---|
| Element text | Decodes entities, joins text/CDATA/descendant text and trims surrounding whitespace |
| `<label>A<b>B</b><![CDATA[C]]></label>` | Reading `label` as `String` produces `ABC` |
| Missing, empty or blank element | Cursor returns `null`; mapping binding is not invoked |
| Attribute | Decodes entities and applies XML whitespace normalization, without trimming |
| Missing or empty attribute | Cursor returns `null`; mapping binding is not invoked |
| Whitespace-only attribute | Remains a value; converters receive the normalized whitespace |

Whitespace-only text runs between markup are discarded during element-text
assembly, then the assembled result is trimmed. Do not use this extraction model
to preserve the exact spacing or markup of a rich-text document.

Only the five predefined XML entities (`amp`, `lt`, `gt`, `apos`, `quot`) and
numeric character references are decoded. Attribute normalization preserves
whitespace introduced by character references; it does not simply apply
`String.trim()` to the attribute.

## Custom conversions, presence and fallbacks

**Unreleased APIs.** Supply a `Function<String, T>` when your application owns the
format or target type. No registry or global configuration is needed.

| Call | Behavior |
|---|---|
| `valueWith(name, converter)` | Converts decoded, trimmed child text |
| `attributeWith(name, converter)` | Converts decoded attribute text without trimming |
| `firstOfWith(converter, names...)` | Converts the first matching alternative in document order |
| `valueWith(name, converter, fallback)` | Calls the converter for nonempty text, otherwise calls a lazy `Supplier<T>` |
| `attributeWith(name, converter, fallback)` | Calls fallback only for a missing or empty attribute; whitespace alone is a value |

Save this as `ConversionExample.java` and compile against the current source build:

```java
import io.github.robsonkades.fletch.Xml;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.function.Function;

public class ConversionExample {
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    static final Function<String, LocalDate> READ_DATE =
            text -> LocalDate.parse(text, DATE_FORMAT);

    record Result(boolean emptyPresent, LocalDate due, LocalDate created,
                  LocalDate delivery, URI link, String channel) {}

    public static void main(String[] args) {
        String xml = """
                <order>
                  <due>18/09/2026</due><created>2026-09-01</created>
                  <link>https://example.com/order/42</link><empty/>
                </order>
                """;
        Result result = Xml.extract(xml, doc -> doc.child("order", order -> {
            boolean emptyPresent = order.exists("empty");
            LocalDate due = order.valueWith("due", READ_DATE);
            LocalDate created = order.value("created", LocalDate.class);
            LocalDate delivery = order.valueWith("delivery", READ_DATE,
                    () -> due.plusDays(2));
            URI link = order.firstOfWith(URI::create, "link", "backupLink");
            String channel = order.attributeWith("channel",
                    text -> text.toUpperCase(Locale.ROOT), () -> "WEB");
            return new Result(emptyPresent, due, created, delivery, link, channel);
        }));
        System.out.println(result.emptyPresent() + " " + result.delivery()
                + " " + result.channel());
    }
}
```

Output: `true 2026-09-20 WEB`. The missing delivery uses a lazy default; the empty
element is structurally present.

For fallback overloads, exactly one function runs, once. A converter returning
`null` returns `null` directly. A converter or parser throwing an exception does
not invoke fallback. A fallback may itself return `null` or throw; that result or
exception propagates. Functions must be non-null and are checked before reading.

Custom functions run synchronously and receive an ordinary String that may be
retained. Handle checked exceptions inside the function, as required by
`java.util.function.Function`. A failed conversion still consumes the selected
child occurrence. Reused functions must be safe for concurrent invocation when
their extractor or mapping is shared.

## Declarative mappings

Build a mapping once, then reuse it. A supplier creates a fresh mutable draft for
each extraction; bindings fill it, and the finisher creates your result. The
following complete `MappingExample.java` uses APIs available in 1.3.0 and includes
repeated items, nested groups, attributes and alternative paths.

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MappingExample {
    record Item(String sku, Integer quantity, List<BigDecimal> adjustments) {}
    record Order(String id, String taxId, List<Item> items, BigDecimal total) {}

    static class OrderDraft {
        String id;
        String taxId;
        BigDecimal total;
        final List<Item> items = new ArrayList<>();
    }
    static class ItemDraft {
        String sku;
        Integer quantity;
        final List<BigDecimal> adjustments = new ArrayList<>();
    }
    static class AdjustmentDraft { BigDecimal amount; }

    static final XmlMapping<Order> ORDER = Xml.mapping(OrderDraft::new)
            .attr("/order@id", (d, v) -> d.id = v.asString())
            .firstOf((d, v) -> d.taxId = v.asString(),
                    "/order/buyer/companyId", "/order/buyer/personId")
            .text("/order/total", (d, v) -> d.total = v.asDecimal())
            .group("/order/item", ItemDraft::new, (order, item) ->
                    order.items.add(new Item(item.sku, item.quantity,
                            List.copyOf(item.adjustments))))
                .attr("@sku", (d, v) -> d.sku = v.asString())
                .text("quantity", (d, v) -> d.quantity = v.asInt())
                .group("adjustment", AdjustmentDraft::new,
                        (item, adjustment) -> item.adjustments.add(adjustment.amount))
                    .text("amount", (d, v) -> d.amount = v.asDecimal())
                .endGroup()
            .endGroup()
            .build(d -> new Order(d.id, d.taxId, List.copyOf(d.items), d.total));

    static final String XML = """
            <order id="A-42">
              <buyer><personId>123</personId></buyer>
              <item sku="BOOK"><quantity>2</quantity>
                <adjustment><amount>1.00</amount></adjustment>
              </item>
              <total>12.30</total>
            </order>
            """;

    public static void main(String[] args) {
        Order order = Xml.extract(XML, ORDER);
        System.out.println(order.total() + " " + order.items().size()
                + " " + order.items().get(0).adjustments());
    }
}
```

Output: `12.30 1 [1.00]`. The application chooses the result's mutability and
validates any required business fields in its finisher. For example, reject a
missing adjustment amount before adding it if the document contract requires one.

### Paths and binding rules

| Declaration | Meaning |
|---|---|
| `.text("/order/total", binding)` | Absolute element path from the document root |
| `.attr("/order@id", binding)` | Attribute on the named element; there is no slash before `@` |
| `.firstOf(binding, path1, path2, ...)` | First **nonempty** alternative to bind, in document order; requires at least two paths |
| `.group(path, draftSupplier, commit)` | One draft per occurrence; commits it to its parent when the occurrence closes |
| Group `.text("product/sku", binding)` | Path relative to the current group |
| Group `.attr("@sku", binding)` / `.attr("product@sku", binding)` | Attribute on the group itself / a descendant |
| Group `.group(...)` and `.endGroup()` | Enter a nested repeating group and return to the enclosing builder |
| `.required(path)` | Early-exit condition for an already declared, nongroup binding |
| `.strictSkip()` | Also compare complete end-tag names in skipped subtrees |
| `.build(finisher)` | Compile an immutable mapping |

Paths consist of literal raw names. They do not interpret XPath predicates,
wildcards or descendant axes. Top-level paths start with `/`; group-relative
paths do not. Prefixes such as `soap:Body` are part of the name.

Ordinary text and attribute bindings run for every nonempty occurrence, in
document order. Assigning a scalar repeatedly keeps the last assigned value;
append in your callback or use a group to collect repeated results. A mapping
`firstOf` binds once per document, or once per occurrence of its enclosing group.
Blank alternatives do not claim that binding. Empty groups still have their own
draft and commit callback.

There are at most **64 bound fields** per mapping. A `firstOf` declaration uses one
field shared by its alternatives. Duplicate targets, duplicate groups, a text
target with descendant bindings, or paths that cross the wrong group scope are
rejected with `XmlException`. Bind fields inside a group's own builder. A text
binding consumes its whole element and cannot also serve as a container for
descendant bindings.

### Early exit is not required-field validation

`required(path)` must refer to an already declared path outside groups. When all
such bindings have received values, extraction stops and the finisher runs.
Fields appearing later remain untouched. Missing required paths do **not**
automatically throw: check the draft in the finisher if absence is an error.

Use early exit only when later fields and groups are unnecessary. Content after
that point is not scanned or validated. Without early exit, bindings are processed
while the mapping traverses the document; this still does not turn extraction
into a whole-document XML validator.

## Mapping values and defaults

A binding receives a borrowed `XmlValue`. Convert it during that callback and
store the result in the draft. Retaining `XmlValue` itself is invalid because its
underlying parse buffer is reused.

| Accessor | Result |
|---|---|
| `asString()` | Decoded String |
| `asCanonical()` | String from a bounded cache when possible; useful for repeating status, currency or state codes |
| `asInt()`, `asLong()`, `asDouble()` | Primitive numeric result |
| `asDecimal()` | `BigDecimal` |
| `asBoolean()` | Primitive boolean |
| `asInstant()` | `Instant` |
| `asEnum(EnumType.class)` | Enum constant |
| `as(Type.class)` — unreleased | Any [built-in value target](#value-types-and-text-handling), including primitive aliases |
| `convert(function)` — unreleased | Application conversion of decoded text |

Typed accessors avoid intermediate Strings where a byte parser exists. The
canonical cache belongs to an engine and survives successive documents. It starts
at 16 slots, grows to at most 1,024 and accepts values up to 64 UTF-8 bytes. A cache
miss, full probe window or oversized value may produce a fresh String. Compare
strings by value; object identity is not an application contract. This is not
`String.intern()`.

Missing and empty values never reach a binding. Put eager defaults in the draft
factory, or compute lazy defaults in the finisher. To distinguish absence from a
converter intentionally returning `null`, retain a separate flag. This complete
`MappingDefaultsExample.java` uses the **unreleased** `convert` accessor:

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.util.Locale;

public class MappingDefaultsExample {
    static class Draft { boolean present; String status; }

    static final XmlMapping<String> STATUS = Xml.mapping(Draft::new)
            .text("/order/status", (d, v) -> {
                d.present = true;
                d.status = v.convert(text -> "unknown".equals(text)
                        ? null : text.toUpperCase(Locale.ROOT));
            })
            .build(d -> d.present ? d.status : defaultStatus());

    static String defaultStatus() { return "NEW"; }

    public static void main(String[] args) {
        System.out.println(Xml.extract("<order/>", STATUS));
        System.out.println(Xml.extract("<order><status>unknown</status></order>", STATUS));
    }
}
```

Output is `NEW`, then `null`. Here `present` records that nonempty text reached the
binding; it does not distinguish a blank element from an absent one. Use cursor
`exists()` when you need that structural distinction.

## Reusable sessions

`Xml.extract` already pools engines. A session gives a worker a dedicated engine
for a batch. Open it with `mapping.openSession()` or `mapping.openSession(limits)`
and close it on the **same thread that created it**. Limits are fixed for the
session and counters reset for each document.

Save as `SessionExample.java` alongside `MappingExample.java`. This uses APIs
available in 1.3.0 and demonstrates limits, recovery after failure and caller-owned
streams:

```java
import io.github.robsonkades.fletch.XmlException;
import io.github.robsonkades.fletch.XmlLimits;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class SessionExample {
    public static void main(String[] args) throws Exception {
        XmlLimits limits = XmlLimits.builder()
                .maxInputBytes(1_048_576)
                .maxDepth(16)
                .maxElements(10_000)
                .maxNameBytes(128)
                .maxAttributesPerElement(32)
                .maxTextBytes(65_536)
                .build();

        try (var session = MappingExample.ORDER.openSession(limits)) {
            System.out.println(session.extract(MappingExample.XML).total());
            try {
                session.extract("<order><total>12.30</wrong></order>");
            } catch (XmlException expected) {
                System.out.println("Invalid document rejected");
            }
            byte[] bytes = MappingExample.XML.getBytes(StandardCharsets.UTF_8);
            System.out.println(session.extract(bytes).items().size());
            try (var input = new ByteArrayInputStream(bytes)) {
                System.out.println(session.extract(input).id());
            }
        }
    }
}
```

Output is `12.30`, `Invalid document rejected`, `1`, then `A-42`, each on a new line.

| Session operation | Contract |
|---|---|
| `extract(byte[] / String / InputStream)` | Fresh draft and counters for each call; reuses engine scratch storage |
| Parse, conversion, I/O or callback failure | Propagates; releases document references; session can process another document |
| Recursive extraction through the same session | `IllegalStateException` before starting another extraction |
| Use or close from another thread | `IllegalStateException`, even if idle; applies to virtual threads too |
| Close from an active callback | `IllegalStateException`; does not cancel the extraction |
| Repeated close on the owner thread | Harmless |
| Extract after close | `IllegalStateException` |

Create sessions inside their worker or virtual-thread task, rather than creating
one and handing it to a different thread. Nested calls through another session or
`Xml.extract` are allowed. A session per document loses most of the intended reuse;
measure a session per batch against the pooled API for your workload.

## Inputs, encodings and stream ownership

| Source | Cursor | Mapping or mapping session |
|---|---|---|
| UTF-8 `byte[]` | Scans caller bytes in place | Scans caller bytes in place |
| `String` | Encodes to UTF-8 before scanning | Encodes to UTF-8 before scanning |
| UTF-8 / US-ASCII `InputStream` | Buffers the entire input | Scans through a reusable sliding window, initially 64 KiB |
| ISO-8859-1 / UTF-16 bytes or stream | Buffers as needed and transcodes to UTF-8 before scanning | Buffers as needed and transcodes to UTF-8 before scanning |

UTF-8 and US-ASCII are handled directly. ISO-8859-1 requires its XML encoding
declaration. UTF-16 is detected from its byte-order mark or byte prefix. Other
encodings are rejected. For an already decoded Java String, the input budget
counts its UTF-8 representation.

Fletch never closes the supplied stream. Use try-with-resources in your application.
Mapping streams can read ahead and can stop before EOF after early exit. They do
not provide framing for multiple concatenated documents; provide one document's
bounded stream per extraction. Cursor streams are fully buffered even when the
extractor needs only a field near the start.

Sliding input storage does not imply constant total application memory. Selected
values, result lists, remembered state, callbacks and transcoding have their own
costs. Do not mutate an input array until extraction has returned.

## Resource limits

`XmlLimits` is immutable and shareable. `XmlLimits.builder()` starts from the
defaults below. Builder setters and configuration accessors have the same names;
`build()` returns the configuration. Limits are inclusive.

| Setting | Default | Allowed values and counting rule |
|---|---|---|
| `maxInputBytes` | `Long.MAX_VALUE` | Positive long; original input bytes, including BOM/declaration; Strings count encoded UTF-8 bytes |
| `maxDepth` | `Integer.MAX_VALUE` | Positive int; root is depth 1; scanned skipped and empty elements count |
| `maxElements` | `Long.MAX_VALUE` | Positive long; scanned start tags, including skipped and empty elements |
| `maxNameBytes` | `Integer.MAX_VALUE` | Positive int; UTF-8 bytes in raw element/attribute names, including prefixes |
| `maxAttributesPerElement` | `1,024` | `0..1,024`; every scanned start tag, including namespace declaration attributes |
| `maxTextBytes` | `16,777,216` (16 MiB) | `0..16,777,216`; selected element content and every scanned attribute value, before decoding/trimming |

For `maxTextBytes`, text, CDATA and descendant text within one selected value share
the same budget. Markup is excluded. Entity spellings count their original bytes:
`&amp;` uses five bytes even though it decodes to one character. Ignored element
text, comments and processing instructions are not selected text. Name budgets
exclude processing-instruction targets and XML-declaration pseudo-attributes.

Arrays and Strings are size-checked in full before extraction. Legacy encodings
count their original bytes before transcoding. A UTF-8 mapping stream is checked
as it is read, including read-ahead; an unread suffix after early exit is not
counted. When EOF must be distinguished from an oversized input at the exact
limit, the reader may consume one extra byte to detect the violation. It does not
drain the remaining stream.

Additional engine ceilings still apply: stream tokens and copied open-name
storage have a 16 MiB ceiling, Java arrays have their usual size limits, and a
mapping supports at most 64 bindings. A streaming start tag or anchored CDATA
section must fit the token window. These limits are not a total heap budget or an
I/O deadline; transcoding and application results can use more memory.

An invalid builder value throws `IllegalArgumentException`. A null configuration
throws `NullPointerException`. Exceeding a configured extraction budget throws
`XmlException` with a diagnostic identifying the limit and location. Earlier
callback effects are not rolled back.

## Validation and errors

### Namespaces and selected XML

Fletch matches literal raw names, without resolving namespace URIs. An unprefixed
`<order xmlns="urn:shop">` matches `order`. A prefixed `<s:order>` matches
`s:order`, not `order`; changing the prefix changes the requested name even if the
namespace URI stays the same. Namespace declarations are available as raw
attributes. XSD validation is not performed.

| XML region | What is checked |
|---|---|
| Traversed elements and selected mixed content | Complete end-tag name matching |
| Every scanned start tag | Attribute quoting/separators, duplicate raw attribute names and forbidden literal `<` in attributes |
| Selected text, CDATA and attribute values | UTF-8, XML character validity and supported entity/character references |
| Skipped subtrees, default mode | Tag balance; complete end-tag names are not compared |
| Skipped subtrees with mapping `strictSkip()` | Complete end-tag name matching as well |
| Scanned comments | Comment terminators and forbidden double-hyphen forms |
| DTD encountered while scanning | Rejected; external entities are never loaded |
| Content after extraction stops | Not scanned or validated |

`strictSkip()` strengthens end-tag checking in ignored subtrees. It does not
validate all ignored text or entity references, XML name/declaration grammar,
namespace bindings, schemas or trailing content. Use an independent validator
when your application requires whole-document validation. Configured limits
apply to the bytes and structure actually encountered under the input contracts
above.

### Exceptions and side effects

| Failure | Exception |
|---|---|
| XML parse error, exceeded extraction limit, unsupported target or invalid boolean | `XmlException` |
| Stream read failure | `XmlException` preserving the underlying `IOException` as its cause |
| Malformed or out-of-range number | `NumberFormatException` |
| Invalid temporal value | `DateTimeParseException` |
| Invalid enum, UUID or character | `IllegalArgumentException` |
| Application converter, binding, draft supplier or finisher throws | Original application exception propagates |
| Session ownership/lifecycle violation | `IllegalStateException` |

Parse diagnostics include byte locations where available. After transcoding,
parser locations refer to the UTF-8 scan buffer; input-size accounting refers to
the original source. Messages are diagnostics, not stable machine-readable error
codes.

Extraction is not transactional. A later error does not undo earlier mutations,
callbacks, database writes or other effects. Build a draft and publish the result
after a successful return when partial effects would be inappropriate.

## Concurrency and object lifetime

| Object | Sharing and lifetime |
|---|---|
| `Xml` static entry points | Can be called concurrently; pooled engines are borrowed per active extraction |
| `XmlExtractor<T>` | Shareable when its callbacks and captured state are safe for concurrent calls |
| Built `XmlMapping<T>` | Immutable definition; shareable when callbacks are safe and suppliers create independent drafts |
| Mapping builder or mutable draft | Confine to the building thread or current extraction |
| `XmlCursor` | Borrowed only inside its active extractor scope; do not retain or share |
| `XmlValue` | Borrowed only during its binding callback; retain converted values instead |
| `XmlMappingSession<T>` | Create, use and close on one owner thread; no same-session reentrancy |
| `XmlLimits` | Immutable and shareable |

Extraction releases references to caller inputs and drafts after success or
failure. Engines reuse scratch buffers and trim unusually large buffers; their
canonical String caches may retain decoded values across documents. Storage is
not securely erased. Closing a session releases its dedicated engine rather
than returning it to the mapping pool.

There is no built-in cancellation or timeout. Closing a session cannot interrupt
an active extraction; stream and callback blocking must be managed by the
application.

## Optional code generation

The experimental generator emits Java 17 source that specializes element and
attribute lookup for an existing `XmlMapping`. It retains the shared scanner,
converters, callbacks, groups, limits and session behavior.

Build the reactor, then use a public static no-argument mapping factory as the
input to `io.github.robsonkades.fletch.XmlCodegen`. Its three arguments are the
factory (`package.Factory#method`), generated class name and output source root.
The generated class's `mapping(definition)` method checks a layout fingerprint
and returns a mapping suitable for `Xml.extract` or `openSession`.

The [codegen guide](codegen/README.md) contains the complete build order, commands,
Maven integration and runtime example. Generated applications require only the
core at runtime; the generator is a build dependency. Use matching core and
generator versions and regenerate after upgrades. The current supported setup
uses the classpath; JPMS integration has not been validated.

Generation does not remove mapping construction, callbacks or result allocation.
Measure your workload before choosing it. Generator limits are 1,024 states,
256 names per transition/attribute slice and 1 MiB of name bytes, in addition to
the DSL's 64-binding limit.

## Architecture and public API

The core combines scanning, raw-name matching and value decoding over bytes.
Cursor reads remember skipped sibling spans when necessary; mappings compile
literal paths into lookup tables and call bindings as those paths are reached.
Values are materialized only when selected. Both paths use engine pooling for
ordinary calls; a session owns its engine explicitly.

| Public type | Responsibility |
|---|---|
| [`Xml`](src/main/java/io/github/robsonkades/fletch/Xml.java) | Input overloads and mapping builder entry point |
| [`XmlExtractor`](src/main/java/io/github/robsonkades/fletch/XmlExtractor.java), [`XmlCursor`](src/main/java/io/github/robsonkades/fletch/XmlCursor.java) | Composable extraction and scoped navigation |
| [`XmlMapping`](src/main/java/io/github/robsonkades/fletch/XmlMapping.java) | Immutable mapping, `Builder`, `GroupBuilder`, session factories |
| [`XmlBinding`](src/main/java/io/github/robsonkades/fletch/XmlBinding.java), [`XmlValue`](src/main/java/io/github/robsonkades/fletch/XmlValue.java) | Draft updates and immediate value conversion |
| [`XmlMappingSession`](src/main/java/io/github/robsonkades/fletch/XmlMappingSession.java) | Dedicated engine lifecycle |
| [`XmlLimits`](src/main/java/io/github/robsonkades/fletch/XmlLimits.java) | Immutable budgets and their builder |
| [`XmlException`](src/main/java/io/github/robsonkades/fletch/XmlException.java) | Unchecked extraction failure |
| [`XmlMappingCode`](src/main/java/io/github/robsonkades/fletch/XmlMappingCode.java) | Experimental generated-lookup protocol; normally used by generated code |

Repository layout:

```text
README.md                         Project guide and API usage
CONTRIBUTING.md                    Development, verification and release process
pom.xml                           Standalone core build
src/main/java/                    Runtime implementation and API Javadoc
src/test/java/                    Unit tests, differential checks and JMH workloads
src/test/resources/               XML fixtures
src/assembly/                     Core benchmark packaging
codegen/pom.xml                   Reactor: core, generator and example
codegen/README.md                 Generator integration guide
codegen/generator/                Build-time generator and its tests
codegen/example/                  Generated consumer, tests and benchmarks
.github/workflows/                CI matrix and manual release automation
```

## Building and testing

Use a JDK 17+ and Maven. Run commands from the repository root. Quoted Maven
properties below work in both Bash and PowerShell.

Core build, tests, source jar and Javadoc jar:

```sh
mvn -B -ntp verify '-Dgpg.skip=true'
```

Full reactor, matching CI:

```sh
mvn -B -ntp -f codegen/pom.xml verify '-Dgpg.skip=true'
```

CI runs that reactor on JDK 17, 21 and 25. The JDK 17 job also synchronizes all
module versions to a temporary value and rebuilds the reactor. Artifact signing
is skipped for local verification and performed by the release workflow.

Generate browsable API documentation:

```sh
mvn -B -ntp javadoc:javadoc
```

Open `target/site/apidocs/index.html`. The attached Javadoc jar is produced by
`verify`, which also generates its API pages under `target/apidocs`. Preserve any
local measurements or other files in build directories before running Maven
`clean`.

## Performance and benchmarks

Performance depends on the XML shape, selected fields, input representation,
conversions, JDK and result construction. A throughput improvement on a small
fixture is not a prediction for every application.

- Reuse extractors and compiled mappings instead of rebuilding them per document.
- Prefer typed accessors when their semantics match the value. Integer, boolean,
  common decimal and common instant forms can parse bytes directly.
- The current cursor also parses valid ASCII `LocalDate` values in `uuuu-MM-dd`
  form, years 0000–9999, from bytes. Other forms and invalid dates use the JDK
  parser. Mapping `as(LocalDate.class)` and custom functions still decode a String.
- Use `asCanonical()` for repeated low-cardinality strings when measurements
  justify it. It has a bounded per-engine memory cost.
- Read cursor fields in document order when convenient to reduce remembered
  siblings. For large UTF-8 streams with fixed paths, measure the mapping API.
- Compare pooled mappings, batch sessions and generated mappings using equivalent
  complete outputs before selecting one.

Build the core JMH harness and list its workloads:

```sh
mvn -B -ntp -Pbenchmarks package '-DskipTests' '-Dgpg.skip=true'
java -jar target/benchmarks.jar -l
```

Run a bounded cursor/mapping/session comparison over a rotating UTF-8 stream corpus:

```sh
java -jar target/benchmarks.jar '.*CorpusBenchmark.*' -p shape=PLAIN -p fanout=16 -p source=STREAM -p budget=DEFAULT -wi 3 -i 5 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff target/corpus-results.json
```

Compare current typed and application-parsed dates:

```sh
java -jar target/benchmarks.jar '.*ValueApiBenchmark.(typedDate|manualDate)' -p presentPercent=100 -wi 3 -i 5 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff target/date-results.json
```

Other included workloads cover extraction, concurrent mappings and attribute
validation. The [codegen guide](codegen/README.md#benchmarks) covers its separate
harness. Some benchmark parameter combinations multiply into long runs; list
workloads and choose parameters deliberately.

These commands are starting points, not a statistical acceptance rule. For a
before/after comparison, retain the source commits, exact command, JDK/VM version,
CPU/OS details, raw JMH JSON and logs. Use equivalent result checks outside timing,
alternate baseline/candidate runs, inspect warmup and iteration drift, and compare
both throughput and `gc.alloc.rate.norm` in bytes per operation. Count independent
forks as replicates; do not present timing noise as a confirmed gain. Save each
run to a distinct output file when collecting evidence.

## Compatibility and upgrades

### From 1.2.0 to 1.3.0

Java 17 remains the minimum and existing public signatures remain available.
Version 1.3.0 adds configurable limits and mapping sessions, and tightens checks
on visited XML:

- Malformed complete end tags, invalid selected characters and malformed or
  duplicate attributes that previously slipped through can now throw.
- Every scanned start tag has a ceiling of 1,024 attributes. Selected text and
  scanned attribute values have a 16 MiB content ceiling before decoding/trimming.
- Default limits leave input size, depth, element count and name length at their
  maximum numeric values. Configure tighter application budgets where needed.
- `strictSkip()` checks skipped end-tag names; it does not validate an entire
  document. Streams remain caller-owned and callback effects are not rolled back.

### Current unreleased additions

The additional cursor methods and `XmlValue` conversion accessors are default
interface methods, preserving existing implementation compatibility. An external
`XmlCursor` implementation must override `exists()` to support non-consuming
probes; its default throws `UnsupportedOperationException` without reading.
Fletch-provided cursors implement it.

Custom converter names are distinct from class-based reads. Existing null/empty,
text normalization and Java exception contracts still apply. Primitive aliases
do not replace nullable results with zero values. Mapping and cursor choice
behavior remain distinct for blank alternatives. Regenerate optional codegen
sources when changing core/generator versions.

Release notes for published versions are available in
[GitHub Releases](https://github.com/robsonkades/fletch/releases). This source guide
is not a promise that unreleased APIs exist in an older Maven artifact.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Root extraction returns `null` | The outer cursor is before the root; enter it with `doc.child("root", extractor)` and match the raw name |
| A prefixed field is absent | Include the literal prefix; namespace URI aliases are not resolved |
| Repeated read returns the next value or `null` | Child reads consume occurrences; attributes can be reread |
| `exists()` is true but conversion uses fallback | Empty and blank elements are present but have no usable text |
| Fallback did not run after a conversion error | Fallback handles missing/empty input, not exceptions or a converter's null result |
| Primitive assignment throws `NullPointerException` | An absent nullable result was unboxed; use a wrapper or explicit fallback |
| Later mapped fields stay at defaults | Check `required()` early exit, actual paths, empty values and group scope |
| Declaring a text path and its descendants fails | Text consumes that element; declare leaf paths or use cursor extraction for a different traversal |
| Large stream consumes substantial memory | Cursor and legacy-encoding streams buffer fully; result lists and selected values also allocate |
| Session throws `IllegalStateException` | Check creating thread, active recursion, close state and callback attempts to close |
| Generated mapping rejects its definition | Regenerate from the matching mapping layout and core/generator version |
| `exists` or `valueWith` does not compile against Maven Central | Those methods are unreleased; use the matching source build or published release once available |

## Contributing and releases

[CONTRIBUTING.md](CONTRIBUTING.md) describes code style, meaningful tests,
performance evidence, pull requests, version synchronization and the manual
release workflow. Report reproducible bugs through
[GitHub Issues](https://github.com/robsonkades/fletch/issues). For security reports,
use the private contact in the contributing guide.

## License

Fletch is distributed under the [Apache License 2.0](LICENSE).
