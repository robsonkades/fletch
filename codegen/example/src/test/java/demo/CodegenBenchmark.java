package demo;

import demo.generated.Attributes48Code;
import demo.generated.Elements4Code;
import demo.generated.Elements48Code;
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Same complete int[] output, rotating prebuilt documents and one mapping per worker. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CodegenBenchmark {
    @Param({"ELEMENTS_4", "ELEMENTS_48", "ATTRIBUTES_48"})
    public String scenario;
    XmlMapping<int[]> dynamic;
    XmlMapping<int[]> generated;
    byte[][] documents;
    private int index;

    @Setup
    public void setup() {
        switch (scenario) {
            case "ELEMENTS_4" -> { dynamic = Definitions.elements4(); generated = Elements4Code.mapping(dynamic); }
            case "ELEMENTS_48" -> { dynamic = Definitions.elements48(); generated = Elements48Code.mapping(dynamic); }
            case "ATTRIBUTES_48" -> { dynamic = Definitions.attributes48(); generated = Attributes48Code.mapping(dynamic); }
            default -> throw new IllegalArgumentException(scenario);
        }
        final int count = scenario.equals("ELEMENTS_4") ? 4 : 48;
        final boolean attributes = scenario.startsWith("ATTRIBUTES");
        documents = new byte[16][];
        final var order = new ArrayList<Integer>();
        for (int field = 0; field < count; field++) order.add(field);
        final Random random = new Random(20260916);
        for (int doc = 0; doc < documents.length; doc++) {
            Collections.shuffle(order, random);
            final int[] expected = new int[count];
            final var xml = new StringBuilder(attributes ? "<r unknown='ignored' " : "<r><unknown/>");
            for (int field : order) {
                final int value = doc * 1000 + field + 1;
                expected[field] = value;
                if (attributes) xml.append('f').append(field).append("='").append(value).append("' ");
                else xml.append("<f").append(field).append('>').append(value).append("</f").append(field).append('>');
            }
            xml.append(attributes ? "/>" : "</r>");
            documents[doc] = xml.toString().getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(expected, Xml.extract(documents[doc], dynamic))
                    || !Arrays.equals(expected, Xml.extract(documents[doc], generated))) {
                throw new IllegalStateException("Generated/dynamic result differs from fixture");
            }
        }
    }

    private byte[] next() {
        final byte[] document = documents[index];
        index = (index + 1) & 15;
        return document;
    }

    @Benchmark
    public int[] dynamic() { return Xml.extract(next(), dynamic); }

    @Benchmark
    public int[] generated() { return Xml.extract(next(), generated); }
}
