# Resource limits and validation coverage

`XmlLimits` adds resource budgets to both extraction styles without changing the
two-argument API. Supply one immutable configuration per extraction:

```java
XmlLimits limits = XmlLimits.builder()
        .maxInputBytes(8 * 1024 * 1024)
        .maxDepth(64)
        .maxElements(100_000)
        .maxNameBytes(256)
        .maxAttributesPerElement(32)
        .maxTextBytes(1024 * 1024)
        .build();

Book book = Xml.extract(bytes, limits, BOOK_MAPPING);
Book same = Xml.extract(bytes, limits, doc -> doc.child("book", BOOK_EXTRACTOR));
```

These settings are examples, not performance targets. The same overloads accept
`String` and `InputStream`. Limits belong to the call, so the same mapping can serve
different document contracts concurrently. Configuration is checked before an engine
is taken from its pool; setters reject invalid values with `IllegalArgumentException`.
Null limits produce `NullPointerException`.

## What each budget measures

| Setting | Unit and coverage | Default / accepted range |
|---|---|---|
| `maxInputBytes` | Original input bytes, including BOM and declaration; see input-form rules below | `Long.MAX_VALUE`; positive `long` |
| `maxDepth` | Document element = 1; includes matched, skipped, nested text and empty elements | `Integer.MAX_VALUE`; positive `int` |
| `maxElements` | Distinct scanned start tags, including skipped and empty elements; cursor replays count once | `Long.MAX_VALUE`; positive `long` |
| `maxNameBytes` | UTF-8 bytes of element/attribute names, including prefixes; also skipped end tags when configured | `Integer.MAX_VALUE`; positive `int` |
| `maxAttributesPerElement` | Attributes of every scanned start tag, including namespace declarations | 1,024; 0–1,024 |
| `maxTextBytes` | Raw UTF-8 content bytes in one selected text value or any scanned attribute, before decoding, normalization or trim | 16 MiB; 0–16 MiB |

The maximum is inclusive. For `maxTextBytes(6)`, `é😀` fits (six UTF-8 bytes), and
` &amp;` fits (six raw bytes, decoded and trimmed to `&`); ` &amp; ` does not fit.
Text runs, CDATA and descendant text within one selected value share the budget.
Markup bytes do not count as content. Unselected element text, comments and
processing-instruction bodies do not consume the text budget. Names of processing
instructions and pseudo-attributes of the declaration are outside the name and
attribute budgets.

Element counting uses the furthest start-tag position already visited. Replaying
buffered bytes counts nothing again, while scanning a later sibling advances the
count. Refill retries of a partial start tag likewise do not count multiple times.

Depth and element limits are checked before creating an element cursor or group
draft. Value limits are checked before conversion/binding, including clean spans,
partial attributes and partial selected CDATA before another refill. Parser scratch
may already contain read-ahead bytes when a content violation is detected.

### Input forms and early exit

| Input | Input-size check | Memory behavior |
|---|---|---|
| `byte[]` | Entire original array before parsing/transcoding, even with early exit | UTF-8 input is scanned in place |
| `String` | Full UTF-8 encoded length before allocating the byte array | Encoded once; isolated surrogates follow the JDK encoder's replacement behavior |
| Cursor `InputStream` | Entire stream while buffering | Buffer growth and read lengths respect the remaining input budget |
| Mapping UTF-8/ASCII `InputStream` | Bytes actually read, including read-ahead; no requirement to read past early exit | Sliding window; unread trailing data is outside the checked budget |
| Mapping legacy-encoded `InputStream` | Entire original stream while buffering, before transcoding | Full buffering and transcoding |

Reads request at most the remaining original-byte budget. If another refill is
needed after exactly reaching it, a single-byte read distinguishes EOF from excess
input. Thus failure can consume `maxInputBytes + 1` bytes; it cannot drain the rest
of an oversized stream. A successful early exit can leave an arbitrarily long
remainder unread. This budget is not proof of the full size of such a stream.

For example, an early-exit mapping can extract `ok` from the first 12 bytes of
`<r><v>ok</v><unread/>` with an input budget of 12. Supplying the same input as an
array fails its up-front size check. Applications requiring a full message-size
check must enforce their framing/size contract before selective extraction.

Legacy encoding limits count source bytes, not expanded UTF-8 bytes. Existing
transcode buffers/intermediate strings can therefore exceed the input limit.
All scanned name/content limits apply to the resulting UTF-8 representation.

### Remaining implementation ceilings

Configurable limits do not replace the independent implementation ceilings:

- Buffered documents must fit a Java byte array.
- A streaming token window and copied open-name scratch each have a 16 MiB cap.
- Streaming start tags and anchored selected CDATA must fit that token window.
- A mapping supports at most 64 bindings.
- The defaults add no practical limit on depth or element count. Configure them
  for workloads where deep structures, large result collections or cursor sibling
  buffers must be constrained.

These are parser budgets, not a total heap bound or a deadline. Results, caller
callbacks, existing pooled buffers and transcode intermediates have separate costs.

## Validation coverage

This table describes content actually visited. Returning from the root cursor
extractor or satisfying a mapping's `required()` paths can leave content unchecked.

| Check | Cursor / ordinary mapping | Mapping with `strictSkip()` |
|---|---|---|
| Complete end-tag names on traversed elements and inside selected mixed text | Checked | Checked |
| End-tag names inside an unselected subtree | Balance only | Complete names compared |
| Attribute quoting, separators, duplicate raw names and literal `<` | Every scanned start tag | Same |
| Valid UTF-8 and XML characters in selected text/CDATA/attributes | Checked | Same |
| Entities and character references in selected values | Five predefined entities and numeric references decoded; invalid references rejected | Same |
| Ignored text/attribute characters and ignored entity references | Not fully validated | Not fully validated |
| Comment double hyphens and terminators of scanned markup | Checked | Same |
| DTD/external entities | DTD markup rejected when encountered; no entity loading | Same |
| Full XML name grammar and declaration grammar | Not provided | Not provided |
| Namespaces and XSD | Raw names are matched; no namespace resolution or schema validation | Same |
| Trailing content after early exit | Not scanned | Not scanned |
| Configured resource limits | Enforced over the coverage described above | Same |

A configured name limit also parses skipped end-tag boundaries to measure their
length; it does not enable end-tag name equality. `strictSkip()` remains the
explicit mapping option for those comparisons. Neither option certifies the
well-formedness of the whole document. `required()` controls early completion;
it does not assert that a missing field must cause an error.

## Failures, ownership and compatibility

Limit violations use `XmlException`, with the applicable limit and a byte offset
in the message. Original input-size failures refer to the original byte count;
scanner offsets after transcoding refer to the UTF-8 representation. Messages
are diagnostic text, not a machine-readable error-code API. Read failures retain
their `IOException` cause. Conversion failures retain their Java exception type.

Streams remain owned by the caller and are never closed by extraction. A failure
releases source and value references; pool return restores default limits. A later
call cannot inherit the previous call's limits or element counters. Reentrant calls
use independent engines, as do concurrent calls.

Callbacks that ran before a later violation may already have changed caller state.
The parser does not roll back side effects. Whole-input byte checks happen before
callbacks; structural/content checks happen when the relevant input is reached.

This change adds a final public type and six three-argument overloads. Existing
two-argument descriptors remain, so existing calls keep their source and binary
shape. Defaults add no input-size, depth, element-count or name-length limit.
Compared with release 1.2.0, stricter malformed-input checks and the enforced
16 MiB content and 1,024-attribute ceilings can reject inputs previously accepted.
Applications relying on those inputs must adjust their document contract before
upgrading. No release version is changed here.
