# Value types and custom conversions

These additions are available from this branch; they are not in the published
1.3.0 artifact. Existing extraction and conversion methods keep their contracts.

## Built-in targets

Read built-in types through the cursor or a mapping:

```java
LocalDate date = cursor.value("date", LocalDate.class);
UUID id = cursor.attribute("id", UUID.class);
// Mapping binding:
(draft, value) -> draft.date = value.as(LocalDate.class)
```

The [README type table](../README.md#supported-value-types) lists all supported
targets. Numbers, booleans, strings, enums and instants retain their previous
behavior. Added targets are `Byte`, `Short`, `Float`, `BigInteger`, `Character`,
`UUID`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetTime`, `OffsetDateTime`,
`ZonedDateTime`, `Duration` and `Period`. Primitive class tokens are aliases for
their corresponding wrappers. No timezone or locale is inferred for local dates
and times.

`Character` represents one non-surrogate UTF-16 code unit, not a whole grapheme:
use `String` for emoji and other supplementary characters. `Float` and `Double`
follow their JDK parsers, including hexadecimal literals, NaN, infinity and
overflow to infinity. `UUID` delegates to `UUID.fromString`; applications needing
a stricter lexical spelling can supply a custom converter.

## Custom formats and domain types

This complete example reuses two functions between cursor and mapping extraction:

```java
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

public class ConversionExample {
    record ProductCode(String value) {}
    record Order(UUID id, LocalDate date, ProductCode code) {}

    static final DateTimeFormatter BR_DATE = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    static final Function<String, LocalDate> READ_DATE = text -> LocalDate.parse(text, BR_DATE);
    static final Function<String, ProductCode> READ_CODE = text ->
            new ProductCode(text.toUpperCase(Locale.ROOT));

    static final class Draft {
        UUID id;
        LocalDate date;
        ProductCode code;
    }

    static final XmlMapping<Order> ORDER = Xml.mapping(Draft::new)
            .attr("/order@id", (d, v) -> d.id = v.as(UUID.class))
            .text("/order/date", (d, v) -> d.date = v.convert(READ_DATE))
            .text("/order/code", (d, v) -> d.code = v.convert(READ_CODE))
            .build(d -> new Order(d.id, d.date, d.code));

    public static void main(String[] args) {
        String xml = "<order id='123e4567-e89b-12d3-a456-426614174000'>"
                + "<date>18/09/2026</date><code>sku-42</code></order>";
        Order fromCursor = Xml.extract(xml, doc -> doc.child("order", order -> new Order(
                order.attribute("id", UUID.class),
                order.valueWith("date", READ_DATE),
                order.valueWith("code", READ_CODE))));
        Order fromMapping = Xml.extract(xml, ORDER);
        if (!fromCursor.equals(fromMapping)) throw new AssertionError("Different results");
        System.out.println(fromMapping);
    }
}
```

The formatter uses strict date resolution, so impossible dates are rejected;
`uuuu` represents the year rather than year-of-era. Formatters can be reused
across threads. See [the JDK formatter contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/format/DateTimeFormatter.html).

Use `attributeWith(name, converter)` for a custom attribute format and
`firstOfWith(converter, names...)` for choice elements. In a mapping, `convert`
works in `text`, `attr`, `firstOf` and group bindings. The same mappings work with
sessions and the optional code generator. A custom function can also return a
collection or use application-specific parsing, normalization and validation.

## Text, absence and failures

| Input or outcome | Contract |
|---|---|
| Element text | Entities and CDATA are decoded; surrounding whitespace is trimmed |
| Attribute text | Entities are decoded and XML whitespace is normalized, without trimming |
| Missing, empty or blank element | Null from cursor; mapping binding and converter are not invoked |
| Missing or empty attribute | Null from cursor; mapping binding and converter are not invoked |
| Whitespace-only attribute | Converter receives the normalized whitespace |
| Converter returns null | Null is a valid result; it is not treated as a parse error or a request to try another field |
| Null converter | `NullPointerException`; cursor navigation has not started |
| Converter throws | The same exception propagates without wrapping |
| Primitive class token with absent input | A nullable boxed result; unboxing it throws `NullPointerException` |

`firstOfWith` selects the first matching element in document order, following the
cursor's existing `firstOf` behavior. A blank selected element does not cause it
to seek the next alternative. Mapping `firstOf` retains its existing rule of
binding the first non-empty alternative; this change does not unify those rules.

XML validation and resource limits run before conversion. Earlier callbacks and
external side effects are not rolled back if conversion fails. A mapping session
can process the next document after failure. Functions execute synchronously;
they must be safe for concurrent calls when their extractor or mapping is shared.
Checked exceptions must be handled by the function, as required by `Function`.
The decoded String may be retained, but a mapping's `XmlValue` remains valid only
inside its binding callback.

## Allocation and compatibility

Existing byte parsers for integer, boolean, common decimal and common instant
values remain in use; `as(Class)` delegates to specialized accessors where
available. Additional temporal targets, floating-point values, big integers,
UUIDs, characters, enums and custom functions parse a decoded String. A custom
function therefore pays for String decoding. For arithmetic on a native numeric
value, existing mapping callbacks can still use `value.asInt()` directly.

The new cursor methods have distinct names to avoid ambiguous calls involving
`null` or functional interfaces. They are default methods, as are the new mapping
accessors, so existing interface implementations remain usable. Functions use the
JDK's `Function` interface; no converter registry, new dependency, reflection or
global configuration is required.
