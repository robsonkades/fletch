# Build-time mapping codegen (experimental)

Codegen specializes element transitions and attribute lookup for a mapping defined
with the existing DSL. It emits ordinary Java 17 source in your application's
package. The scanner, validation, value conversion, callbacks, groups, limits and
sessions continue to use the Fletch engine.

This is a local prototype, not a published artifact. The normal core build remains
`mvn verify -Dgpg.skip=true`. Build the core, generator and executable example together:

```sh
mvn -f codegen/pom.xml verify -Dgpg.skip=true
```

## Define, generate, compile

Put the mapping in a public, static, no-argument factory returning `XmlMapping<T>`.
The factory and its dependencies must compile without referring to generated code.
For example, [Definitions.java](example/src/main/java/demo/Definitions.java) declares:

```java
public static XmlMapping<Order> order() {
    return Xml.mapping(Draft::new)
            .attr("/order@id", (d, v) -> d.id = v.asString())
            .text("/order/total", (d, v) -> d.total = v.asDecimal())
            .build(d -> new Order(d.id, d.total));
}
```

The generator's command-line arguments are:

```text
io.github.robsonkades.fletch.XmlCodegen <public.Factory#method> <package.GeneratedClass> <source-root>
```

After the reactor build, this PowerShell command reproduces the example's source:

```powershell
java -cp 'target/fletch-1.2.0.jar;codegen/generator/target/fletch-codegen-1.2.0.jar;codegen/example/target/classes' `
  io.github.robsonkades.fletch.XmlCodegen 'demo.Definitions#order' `
  demo.generated.OrderCode codegen/example/target/generated-sources/fletch
```

Use `:` instead of `;` as the classpath separator on Linux/macOS. The build tool
requires a JDK; the generated application's runtime does not require a compiler.

The [example POM](example/pom.xml) automates three steps in this order:

1. Compile `Definitions.java` and `NfeDefinition.java` during `generate-sources`.
2. Run `XmlCodegen` with `exec-maven-plugin`, adding the generated-source directory.
3. Compile the application and generated sources in the normal `compile` phase.

For an application with multiple factory source files, adjust the compiler includes
or put those definitions in a separate module built first. Keep generation before
the application compilation, and keep the factory independent of generated classes.
The generator dependency has `provided` scope in the example; it is only a build tool.

## Use the generated mapping

Create and retain the generated mapping once:

```java
private static final XmlMapping<Definitions.Order> ORDER =
        demo.generated.OrderCode.mapping(Definitions.order());

// byte[], String and InputStream all retain the usual Xml.extract contract.
Definitions.Order order = Xml.extract(input, ORDER);
```

`ORDER.openSession()` and `ORDER.openSession(limits)` work as usual. Mappings can be
shared when their callbacks are safe for concurrent calls on separate drafts;
sessions belong to the thread that creates them.

Run the compiled example with only the core and consumer jars (PowerShell):

```powershell
java --limit-modules java.base `
  -cp 'target/fletch-1.2.0.jar;codegen/example/target/fletch-codegen-example-1.2.0.jar' demo.Main
```

Expected output: `Order[id=42, total=12.30]`. This command also verifies that the
application runs without the generator or the `java.compiler` module.

## Generation contract

- Generation is deterministic for the same mapping layout and qualified class name.
  Identical output leaves the file unchanged; different output replaces only a file
  carrying the generator's header. Generated files live under `target/` in the example.
- The factory executes application code during the build. Keep its layout deterministic
  and independent of environment variables, external services and time. Extraction
  callbacks and draft suppliers are retained, not executed by the generator.
- Associating the generated program checks a SHA-256 layout fingerprint once and
  creates a mapping with its own pool. A stale or incompatible layout throws
  `IllegalArgumentException` with a request to regenerate. Callback captures and options
  that do not change lookup layout retain the supplied definition's behavior.
- Matching checks the entire name after the hash check, including bytes beyond the
  scanner's hash prefix. Hash collisions do not make different names equivalent.
- `XmlMappingCode` is an experimental generator protocol, not an application plugin
  API. Use matching core/generator versions and regenerate when upgrading. The supported
  build uses the classpath; JPMS integration has not been validated.
- Prototype bounds: at most 1,024 states, 256 names in each transition/attribute slice,
  and 1 MiB of name bytes. Existing DSL bounds, including 64 bindings, still apply.

This phase does not generate direct field writes, remove callback dispatch or replace
the parser. The DSL still constructs mapping tables at startup; generated name bytes
and classes add footprint. No startup, memory or universal throughput gain is promised.

## Verification and measurements

The reactor runs the core suite, compilation/differential tests for generated code
and executable example tests on Java 17/21/25 in CI. Coverage includes hash collisions,
shuffled fields, groups, encodings, malformed input, limits, session recovery/ownership
and concurrent use.

Build the optional JMH harness:

```sh
mvn -f codegen/pom.xml -Pbenchmarks package -DskipTests -Dgpg.skip=true
java -jar codegen/example/target/benchmarks.jar 'demo.CodegenBenchmark.*' -prof gc
```

Fixtures rotate 16 prebuilt documents with shuffled fields and different values. Both
variants return and validate the complete result. Generation and compilation happen
before JMH, and the generator is excluded from the benchmark jar.

After the shared scanner's duplicate-attribute optimization, a direct comparison
of generated lookup and the DSL used four Java 21 pairs with 48 selected attributes:
**+2.70%** generated throughput, descriptive 95% interval **[-0.58%, +6.08%]**,
and approximately **208 B/document** for both routes. This did not establish
superiority or equivalence within ±5%. One generated fork drifted down 5.26%
during measurement and remains included. The retained generator is optional;
measure your own mapping before adopting it. See the
[results and archived data](../docs/benchmark-results/README.md#optional-codegen).

The example also includes a [NF-e mapping](example/src/main/java/demo/NfeDefinition.java)
with dates, decimals, alternative fields and repeated item groups. Its benchmark
uses the existing repository XML unchanged for one item and a synthetic expansion
for 50 items. An independent DOM oracle verifies the complete output before timing;
DOM is not needed by the generated application's runtime.

```sh
java -jar codegen/example/target/benchmarks.jar 'demo.NfeCodegenBenchmark.*' -p items=1,50 -prof gc
```

In the [NF-e comparison](../docs/benchmark-results/README.md#optional-codegen),
four Java 21 pairs per size gave **+0.17%** for the original XML (descriptive 95%
interval **[-2.76%, +3.19%]**) and **+3.19%** for 50 synthetic items
(**[+2.11%, +4.27%]**). Allocation remained approximately **1,192 / 14,208 B/document**
for both routes. Neither case reached the predeclared 5% practical threshold.
The generated consumer needs only `java.base` at runtime. These measurements do
not represent a user's production document distribution or establish a general
reason to prefer generated mappings.
