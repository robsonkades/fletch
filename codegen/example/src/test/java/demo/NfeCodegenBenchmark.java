package demo;

import demo.generated.NfeCode;
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

/** One fixture per trial: original NF-e or its explicitly synthetic 50-item expansion. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NfeCodegenBenchmark {
    @Param({"1", "50"})
    public int items;
    private byte[] document;
    private XmlMapping<NfeDefinition.Nfe> dynamic;
    private XmlMapping<NfeDefinition.Nfe> generated;

    @Setup
    public void setup() throws Exception {
        dynamic = NfeDefinition.mapping();
        generated = NfeCode.mapping(dynamic);
        document = NfeFixture.load(items);
        final var expected = NfeFixture.expected(document);
        if (expected.items().size() != items || !expected.equals(Xml.extract(document, dynamic))
                || !expected.equals(Xml.extract(document, generated))) {
            throw new IllegalStateException("NF-e extraction differs from independent DOM oracle");
        }
    }

    @Benchmark
    public NfeDefinition.Nfe dynamic() { return Xml.extract(document, dynamic); }

    @Benchmark
    public NfeDefinition.Nfe generated() { return Xml.extract(document, generated); }
}
