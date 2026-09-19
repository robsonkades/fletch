package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

public class XmlCodegenTest {
    @TempDir Path directory;

    enum Source {
        BYTES, STRING, STREAM;
        <T> T extract(final XmlMapping<T> mapping, final String xml) {
            return switch (this) {
                case BYTES -> Xml.extract(xml.getBytes(StandardCharsets.UTF_8), mapping);
                case STRING -> Xml.extract(xml, mapping);
                case STREAM -> Xml.extract(chunked(xml.getBytes(StandardCharsets.UTF_8)), mapping);
            };
        }
    }

    record Item(String code, int quantity, String choice, List<String> notes) {}
    record Order(String id, String choice, List<Item> items, BigDecimal total) {}
    static final class OrderDraft {
        String id, choice;
        List<Item> items = new ArrayList<>();
        BigDecimal total;
        Order finish() { return new Order(id, choice, List.copyOf(items), total); }
    }
    static final class ItemDraft {
        String code, choice;
        int quantity;
        List<String> notes = new ArrayList<>();
    }

    private static XmlMapping<Order> orders() {
        return Xml.mapping(OrderDraft::new).strictSkip()
                .attr("/r@id", (d, v) -> d.id = v.asString())
                .firstOf((d, v) -> d.choice = v.asString(), "/r/a", "/r/b")
                .group("/r/item", ItemDraft::new, (d, item) -> d.items.add(
                        new Item(item.code, item.quantity, item.choice, List.copyOf(item.notes))))
                    .attr("@code", (d, v) -> d.code = v.asString())
                    .text("qty", (d, v) -> d.quantity = v.asInt())
                    .firstOf((d, v) -> d.choice = v.asString(), "x", "y")
                    .group("note", () -> new String[1], (d, note) -> d.notes.add(note[0]))
                        .text("v", (d, v) -> d[0] = v.asString()).endGroup()
                    .endGroup()
                .text("/r/total", (d, v) -> d.total = v.asDecimal())
                .build(OrderDraft::finish);
    }

    @ParameterizedTest @EnumSource(Source.class)
    void preservesGroupsChoicesUnicodeAttributesAndInputForms(final Source source) throws Exception {
        final var definition = orders();
        try (var compiled = compile(definition)) {
            final String xml = "<r ignored='x' id='A&amp;B'><b>first</b><a>ignored</a>"
                    + "<item code='á'><y>Y</y><x>ignored</x><note><v>ação &amp; Ω</v></note><qty>2</qty></item>"
                    + "<unknown><nested/></unknown><item code='二'><qty>3</qty><x>X</x><y>ignored</y>"
                    + "<note><v><![CDATA[<ok>]]></v></note></item><total>12.30</total></r>";
            final Order expected = new Order("A&B", "first", List.of(
                    new Item("á", 2, "Y", List.of("ação & Ω")),
                    new Item("二", 3, "X", List.of("<ok>"))), new BigDecimal("12.30"));
            assertEquals(expected, source.extract(definition, xml));
            assertEquals(expected, source.extract(compiled.mapping, xml));
        }
    }

    @ParameterizedTest @EnumSource(Source.class)
    void generatedMappingsPreserveAdditionalTypesAndCustomConversionFailures(final Source source) throws Exception {
        final var formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
        final var failure = new IllegalArgumentException("custom date rejected");
        final Function<String, LocalDate> converter = text -> {
            if (text.equals("bad")) throw failure;
            return LocalDate.parse(text, formatter);
        };
        final var definition = Xml.mapping(() -> new Object[4])
                .attr("/r@id", (d, v) -> d[0] = v.as(UUID.class))
                .text("/r/date", (d, v) -> d[1] = v.as(LocalDate.class))
                .text("/r/local", (d, v) -> d[2] = v.convert(converter))
                .text("/r/count", (d, v) -> d[3] = v.as(BigInteger.class))
                .build(d -> d);
        final String xml = "<r id='123e4567-e89b-12d3-a456-426614174000'>"
                + "<local>18/09/2026</local><date>2026-09-19</date>"
                + "<count>999999999999999999999999999</count></r>";
        final Object[] expected = {UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 18),
                new BigInteger("999999999999999999999999999")};
        try (var compiled = compile(definition)) {
            assertArrayEquals(expected, source.extract(compiled.mapping, xml));
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> source.extract(compiled.mapping, xml.replace("18/09/2026", "bad"))));
            assertArrayEquals(expected, source.extract(compiled.mapping, xml));
            try (var session = compiled.mapping.openSession()) {
                assertSame(failure, assertThrows(IllegalArgumentException.class,
                        () -> session.extract(xml.replace("18/09/2026", "bad"))));
                assertArrayEquals(expected, session.extract(xml));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void comparesCompleteNamesDespiteIdenticalHashes(final boolean attributes) throws Exception {
        final String first = "abcdefghijklmnopAAAA", second = "abcdefghijklmnopBBBB", unknown = "abcdefghijklmnopCCCC";
        final byte[] a = first.getBytes(StandardCharsets.UTF_8), b = second.getBytes(StandardCharsets.UTF_8);
        assertEquals(Swar.hash(a, 0, a.length), Swar.hash(b, 0, b.length));
        final var builder = Xml.mapping(() -> new int[2]);
        if (attributes) {
            builder.attr("/r@" + first, (d, v) -> d[0] = v.asInt()).attr("/r@" + second, (d, v) -> d[1] = v.asInt());
        } else {
            builder.text("/r/" + first, (d, v) -> d[0] = v.asInt()).text("/r/" + second, (d, v) -> d[1] = v.asInt());
        }
        final var definition = builder.build(d -> d);
        final String xml = attributes
                ? "<r " + unknown + "='999' " + second + "='2' " + first + "='1'/>"
                : "<r><" + unknown + ">999</" + unknown + "><" + second + ">2</" + second + "><" + first + ">1</" + first + "></r>";
        try (var compiled = compile(definition)) {
            for (Source source : Source.values()) assertArrayEquals(new int[]{1, 2}, source.extract(compiled.mapping, xml));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preservesWideShuffledMappingsAndMethodBlockBoundaries(final boolean attributes) throws Exception {
        final int count = 64;
        final var builder = Xml.mapping(() -> new int[count]);
        for (int field = 0; field < count; field++) {
            final int index = field;
            if (attributes) builder.attr("/r@f" + index, (d, v) -> d[index] = v.asInt());
            else builder.text("/r/p" + index + "/v", (d, v) -> d[index] = v.asInt());
        }
        final var definition = builder.build(d -> d);
        final Random random = new Random(20260916);
        final List<Integer> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) fields.add(i);
        try (var compiled = compile(definition)) {
            for (int document = 0; document < 100; document++) {
                Collections.shuffle(fields, random);
                final int[] expected = new int[count];
                final StringBuilder xml = new StringBuilder(attributes ? "<r unknown='x' " : "<r><unknown/>");
                for (int field : fields) {
                    final int value = random.nextInt(200_001) - 100_000;
                    expected[field] = value;
                    if (attributes) xml.append('f').append(field).append("='").append(value).append("' ");
                    else xml.append("<p").append(field).append("><v>").append(value).append("</v></p").append(field).append('>');
                }
                xml.append(attributes ? "/>" : "</r>");
                for (Source source : Source.values()) {
                    assertArrayEquals(expected, source.extract(definition, xml.toString()));
                    assertArrayEquals(expected, source.extract(compiled.mapping, xml.toString()));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wideLookupsCompareFullNamesWhenAllHashesCollide(final boolean attributes) throws Exception {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < 64; i++) names.add("abcdefghijklmnop" + (1000 + i));
        assertWideNames(attributes, names, "abcdefghijklmnop9999");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wideLookupsDistinguishDifferentHashesWithTheSameFold(final boolean attributes) throws Exception {
        final List<String> names = new ArrayList<>();
        for (char c = 'a'; c <= 'p'; c++) names.add(c + "aaa" + c + "aaa");
        final byte[] first = names.get(0).getBytes(StandardCharsets.UTF_8);
        final byte[] second = names.get(1).getBytes(StandardCharsets.UTF_8);
        final long a = Swar.hash(first, 0, first.length), b = Swar.hash(second, 0, second.length);
        assertNotEquals(a, b);
        assertEquals((int) (a ^ (a >>> 32)), (int) (b ^ (b >>> 32)));
        assertWideNames(attributes, names, "zaaazaaa");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void maximumBindingCountPreservesEveryTargetAndRejectsUnknownNames(final boolean attributes) throws Exception {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < 64; i++) names.add("f" + i);
        assertWideNames(attributes, names, "f64");
    }

    @Test
    void manyWideStatesKeepTheirOwnTargetsAndLargeMetadataCompiles() throws Exception {
        // One choice can name many transitions while respecting the 64-binding limit.
        // These 992 states and 64 bindings exercise dispatch across multiple state blocks.
        final var builder = Xml.mapping(() -> new int[64]);
        final String[] paths = new String[15 * 65];
        for (int state = 0; state < 15; state++) {
            for (int field = 0; field < 65; field++) paths[state * 65 + field] = "/r/p" + state + "/f" + field;
        }
        builder.firstOf((d, v) -> d[0] = v.asInt(), paths);
        final int[] expected = new int[64];
        expected[0] = 7;
        for (int state = 0; state < 7; state++) {
            for (int field = 0; field < 9; field++) {
                final int index = 1 + state * 9 + field;
                expected[index] = index + 10;
                builder.attr("/r/p" + state + "@f" + field, (d, v) -> d[index] = v.asInt());
            }
        }
        final StringBuilder xml = new StringBuilder("<r><unknown/><p14><f64>7</f64></p14>");
        for (int state = 6; state >= 0; state--) {
            xml.append("<p").append(state).append(" unknown='999'");
            for (int field = 8; field >= 0; field--) {
                xml.append(" f").append(field).append("='").append(state * 9 + field + 11).append('\'');
            }
            xml.append("/>");
        }
        xml.append("</r>");
        try (var compiled = compile(builder.build(d -> d))) {
            for (Source source : Source.values()) assertArrayEquals(expected, source.extract(compiled.mapping, xml.toString()));
        }
    }

    @Test
    void maximumTransitionWidthSupportsChoiceAliasesAndUnknowns() throws Exception {
        final String[] paths = new String[256];
        for (int i = 0; i < paths.length; i++) paths[i] = "/r/f" + i;
        final var definition = Xml.mapping(() -> new int[1]).firstOf((d, v) -> d[0] = v.asInt(), paths).build(d -> d);
        try (var compiled = compile(definition)) {
            for (int i = 0; i < paths.length; i++) {
                final String xml = "<r><unknown>999</unknown><f" + i + ">" + (i + 1) + "</f" + i + "></r>";
                for (Source source : Source.values()) assertArrayEquals(new int[]{i + 1}, source.extract(compiled.mapping, xml));
            }
        }
    }

    @Test
    void rejectsNamesBeyondThePerStateLimit() {
        final String[] paths = new String[257];
        for (int i = 0; i < paths.length; i++) paths[i] = "/r/f" + i;
        final var builder = Xml.mapping(() -> new int[1]).firstOf((d, v) -> d[0] = v.asInt(), paths);
        assertThrows(IllegalArgumentException.class,
                () -> XmlCodegen.generate(builder.build(d -> d), "consumer.TooWide"));
    }

    private void assertWideNames(final boolean attributes, final List<String> names, final String unknown) throws Exception {
        final var builder = Xml.mapping(() -> new int[names.size()]);
        final List<Integer> order = new ArrayList<>();
        final int[] expected = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            expected[i] = i + 1;
            order.add(i);
            if (attributes) builder.attr("/r@" + names.get(i), (d, v) -> d[index] = v.asInt());
            else builder.text("/r/" + names.get(i), (d, v) -> d[index] = v.asInt());
        }
        Collections.shuffle(order, new Random(20260917));
        final StringBuilder xml = new StringBuilder(attributes ? "<r " + unknown + "='999' " : "<r><" + unknown + ">999</" + unknown + ">");
        for (int field : order) {
            if (attributes) xml.append(names.get(field)).append("='").append(field + 1).append("' ");
            else xml.append('<').append(names.get(field)).append('>').append(field + 1).append("</").append(names.get(field)).append('>');
        }
        xml.append(attributes ? "/>" : "</r>");
        final var definition = builder.build(d -> d);
        try (var compiled = compile(definition)) {
            for (Source source : Source.values()) {
                assertArrayEquals(expected, source.extract(definition, xml.toString()), "DSL " + source);
                assertArrayEquals(expected, source.extract(compiled.mapping, xml.toString()), "generated " + source);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"<r><v>bad</v></r>", "<r><v>1</wrong></r>",
            "<r a='1' a='2'><v>1</v></r>", "<r><!--bad--comment--><v>1</v></r>", "<r><skip></bad><v>1</v></r>"})
    void preservesErrorsAndSessionRecovery(final String invalid) throws Exception {
        final var definition = values(true);
        try (var compiled = compile(definition); var session = compiled.mapping.openSession()) {
            final RuntimeException expected = assertThrows(RuntimeException.class, () -> Xml.extract(invalid, definition));
            final RuntimeException actual = assertThrows(RuntimeException.class, () -> session.extract(invalid));
            assertEquals(expected.getClass(), actual.getClass());
            assertArrayEquals(new int[]{7}, session.extract("<r><v>7</v></r>"));
        }
    }

    @Test
    void retainsLimitsEarlyExitAndBorrowedStreamOwnership() throws Exception {
        final var definition = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> d[0] = v.asInt())
                .required("/r/v").build(d -> d);
        try (var compiled = compile(definition)) {
            final String xml = "<r><v>7</v><ignored>"; // Same intentional early-exit contract.
            assertArrayEquals(Xml.extract(xml, definition), Xml.extract(xml, compiled.mapping));
            final XmlLimits limited = XmlLimits.builder().maxElements(1).build();
            assertThrows(XmlException.class, () -> Xml.extract(xml, limited, compiled.mapping));
            assertThrows(XmlException.class, () -> Xml.extract(xml.getBytes(StandardCharsets.UTF_8), limited, compiled.mapping));
            assertThrows(XmlException.class, () -> Xml.extract(chunked(xml.getBytes(StandardCharsets.UTF_8)), limited, compiled.mapping));
            final class Borrowed extends ByteArrayInputStream {
                boolean closed;
                Borrowed() { super("<r><v>7</v></r>".getBytes(StandardCharsets.UTF_8)); }
                @Override public void close() { closed = true; }
            }
            final Borrowed stream = new Borrowed();
            try (var session = compiled.mapping.openSession()) { assertArrayEquals(new int[]{7}, session.extract(stream)); }
            assertFalse(stream.closed);
        }
    }

    @Test
    @Timeout(20)
    void retainsSessionOwnershipReentrancyAndRecoveryAfterIoFailure() throws Exception {
        final AtomicReference<XmlMappingSession<int[]>> active = new AtomicReference<>();
        final var definition = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> {
            assertThrows(IllegalStateException.class, () -> active.get().extract("<r><v>9</v></r>"));
            assertThrows(IllegalStateException.class, () -> active.get().close());
            d[0] = v.asInt();
        }).build(d -> d);
        try (var compiled = compile(definition); var session = compiled.mapping.openSession()) {
            active.set(session);
            final var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    assertThrows(IllegalStateException.class, () -> session.extract("<r><v>9</v></r>"));
                    assertThrows(IllegalStateException.class, session::close);
                }).get(5, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            final IOException failure = new IOException("broken stream");
            final InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw failure; }
            };
            assertSame(failure, assertThrows(XmlException.class, () -> session.extract(broken)).getCause());
            assertArrayEquals(new int[]{7}, session.extract("<r><v>7</v></r>"));
        }
    }

    @Test
    void preservesTranscodingAndRejectsInvalidUtf8() throws Exception {
        final var definition = Xml.mapping(() -> new String[1]).text("/r/v", (d, v) -> d[0] = v.asString()).build(d -> d[0]);
        try (var compiled = compile(definition)) {
            final byte[][] inputs = {
                    "<r><v>ação Ω</v></r>".getBytes(StandardCharsets.UTF_16),
                    "<?xml version='1.0' encoding='ISO-8859-1'?><r><v>ação</v></r>".getBytes(StandardCharsets.ISO_8859_1)
            };
            for (byte[] input : inputs) {
                assertEquals(Xml.extract(input, definition), Xml.extract(input, compiled.mapping));
                assertEquals(Xml.extract(input, definition), Xml.extract(chunked(input), compiled.mapping));
            }
            final byte[] invalid = "<r><v>x</v></r>".getBytes(StandardCharsets.UTF_8);
            invalid[6] = (byte) 0xff;
            assertThrows(XmlException.class, () -> Xml.extract(invalid, compiled.mapping));
        }
    }

    @Test
    void rejectsChangedLayoutButAllowsDifferentCallbacksAndOptions() throws Exception {
        final var definition = values(false);
        try (var compiled = compile(definition)) {
            assertThrows(IllegalArgumentException.class, () -> compiled.rebind(
                    Xml.mapping(() -> new int[1]).text("/r/other", (d, v) -> d[0] = v.asInt()).build(d -> d)));
            final var callbacks = Xml.mapping(() -> new int[1]).strictSkip()
                    .text("/r/v", (d, v) -> d[0] = v.asInt() + 100).required("/r/v").build(d -> d);
            assertArrayEquals(new int[]{107}, Xml.extract("<r><v>7</v></r>", compiled.rebind(callbacks)));
            assertArrayEquals(new int[]{7}, Xml.extract("<r><v>7</v></r>", definition));
            assertThrows(NullPointerException.class, () -> compiled.rebind(null));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(30)
    void sharesGeneratedCodeAcrossWorkersWithIndependentResults(final boolean sessions) throws Exception {
        final int count = 8;
        final CyclicBarrier barrier = new CyclicBarrier(count);
        final var definition = Xml.mapping(() -> new int[1]).text("/r/v", (d, v) -> {
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
            d[0] = v.asInt();
        }).build(d -> d);
        try (var compiled = compile(definition)) {
            final var executor = Executors.newFixedThreadPool(count);
            try {
                final List<Future<?>> jobs = new ArrayList<>();
                for (int worker = 0; worker < count; worker++) {
                    final int id = worker;
                    jobs.add(executor.submit(() -> {
                        try (var session = compiled.mapping.openSession()) {
                            for (int iteration = 0; iteration < 20; iteration++) {
                                final int expected = id * 1000 + iteration;
                                final String xml = "<r><v>" + expected + "</v></r>";
                                assertArrayEquals(new int[]{expected}, sessions ? session.extract(xml) : Xml.extract(xml, compiled.mapping));
                            }
                        }
                    }));
                }
                for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void generatedLookupIsUsedInsteadOfSilentlyFallingBack() throws Exception {
        final var definition = values(false);
        try (var compiled = compile(definition, s -> s.replace("hash == 0x", "false && hash == 0x"))) {
            assertArrayEquals(new int[]{0}, Xml.extract("<r><v>7</v></r>", compiled.mapping));
        }
    }

    @Test
    void splitsLargeNameConstantsAndPreservesEmptyMappings() throws Exception {
        final String name = "n".repeat(80_000);
        final var definition = Xml.mapping(() -> new int[1]).text("/" + name, (d, v) -> d[0] = v.asInt()).build(d -> d);
        try (var compiled = compile(definition)) {
            assertArrayEquals(new int[]{7}, Xml.extract("<" + name + ">7</" + name + ">",
                    XmlLimits.builder().maxNameBytes(100_000).build(), compiled.mapping));
        }
        try (var compiled = compile(Xml.mapping(() -> 7).build(d -> d))) {
            assertEquals(7, Xml.extract("<r/>", compiled.mapping));
        }
    }

    @Test
    void generatesDeterministicallyAndDoesNotOverwriteManualFiles() throws Exception {
        final String name = "client.Generated";
        final String source = XmlCodegen.generate(values(false), name);
        assertEquals(source, XmlCodegen.generate(values(false), name));
        final Path output = XmlCodegen.write(values(false), name, directory);
        Files.setLastModifiedTime(output, FileTime.fromMillis(1000));
        XmlCodegen.write(values(false), name, directory);
        assertEquals(FileTime.fromMillis(1000), Files.getLastModifiedTime(output));
        Files.writeString(output, source.replace("\n", "\r\n"));
        XmlCodegen.write(values(false), name, directory);
        assertEquals(source, Files.readString(output));
        Files.writeString(output, "// hand-written\nclass Generated {}\n");
        assertThrows(IOException.class, () -> XmlCodegen.write(values(false), name, directory));
        assertEquals("// hand-written\nclass Generated {}\n", Files.readString(output));
    }

    @ParameterizedTest @ValueSource(strings = {"NoPackage", "../Escape", "p.class", "p.record", "java.lang.Generated",
            "io.github.robsonkades.fletch.Generated", "p.bad-name", "p..Generated"})
    void rejectsInvalidGeneratedClassNames(final String name) {
        assertThrows(IllegalArgumentException.class, () -> XmlCodegen.generate(values(false), name));
    }

    @Test
    void rejectsOversizedDefinitionsBeforeWritingSource() {
        final var mapping = Xml.mapping(() -> new int[1]).text("/x".repeat(1024), (d, v) -> d[0] = v.asInt()).build(d -> d);
        assertThrows(IllegalArgumentException.class, () -> XmlCodegen.write(mapping, "client.TooLarge", directory));
        assertFalse(Files.exists(directory.resolve("client/TooLarge.java")));
    }

    public static final class Factory {
        public static XmlMapping<int[]> valid() { return values(false); }
        public static String wrongType() { return "wrong"; }
        public XmlMapping<int[]> instance() { return values(false); }
        public static XmlMapping<int[]> missing() { return null; }
    }

    @Test
    void commandLineValidatesFactoryAndCreatesCompilableOutput() throws Exception {
        final String factory = Factory.class.getName();
        XmlCodegen.main(new String[]{factory + "#valid", "client.FromCli", directory.toString()});
        assertTrue(Files.readString(directory.resolve("client/FromCli.java")).contains("class FromCli"));
        assertThrows(IllegalArgumentException.class, () -> XmlCodegen.main(new String[0]));
        for (String method : List.of("wrongType", "instance")) {
            assertThrows(IllegalArgumentException.class, () -> XmlCodegen.main(
                    new String[]{factory + "#" + method, "client.Invalid", directory.toString()}));
        }
        assertThrows(NullPointerException.class, () -> XmlCodegen.main(
                new String[]{factory + "#missing", "client.Invalid", directory.toString()}));
    }

    private static XmlMapping<int[]> values(final boolean strict) {
        final var builder = Xml.mapping(() -> new int[1]);
        if (strict) builder.strictSkip();
        return builder.text("/r/v", (d, v) -> d[0] = v.asInt()).build(d -> d);
    }

    private static InputStream chunked(final byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(final byte[] target, final int offset, final int length) {
                return super.read(target, offset, Math.min(length, 3));
            }
        };
    }

    private <T> Compiled<T> compile(final XmlMapping<T> definition) throws Exception {
        return compile(definition, UnaryOperator.identity());
    }

    private <T> Compiled<T> compile(final XmlMapping<T> definition, final UnaryOperator<String> transform) throws Exception {
        final Path root = Files.createTempDirectory(directory, "compile-");
        final String name = "consumer.Generated";
        final Path source = XmlCodegen.write(definition, name, root);
        Files.writeString(source, transform.apply(Files.readString(source)));
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests need a JDK");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            final var options = List.of("--release", "17", "-proc:none", "-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "-d", root.toString());
            assertTrue(compiler.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjects(source)).call(), () -> diagnostics.getDiagnostics().toString());
        }
        final URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, XmlCodegen.class.getClassLoader());
        try {
            final Method factory = Class.forName(name, true, loader).getMethod("mapping", XmlMapping.class);
            return new Compiled<>(factory, loader, definition);
        } catch (Throwable failure) {
            loader.close();
            throw failure;
        }
    }

    private static final class Compiled<T> implements AutoCloseable {
        final Method factory;
        final URLClassLoader loader;
        final XmlMapping<T> mapping;
        Compiled(final Method factory, final URLClassLoader loader, final XmlMapping<T> definition) throws Exception {
            this.factory = factory;
            this.loader = loader;
            mapping = rebind(definition);
        }
        @SuppressWarnings("unchecked")
        XmlMapping<T> rebind(final XmlMapping<T> definition) throws Exception {
            try { return (XmlMapping<T>) factory.invoke(null, definition); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw (Error) failure.getCause();
            }
        }
        @Override public void close() throws IOException { loader.close(); }
    }
}
