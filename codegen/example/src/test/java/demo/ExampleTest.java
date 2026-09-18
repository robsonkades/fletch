package demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExampleTest {
    @Test
    void runsGeneratedConsumer() { assertDoesNotThrow(() -> Main.main(new String[0])); }

    @ParameterizedTest @ValueSource(strings = {"ELEMENTS_4", "ELEMENTS_48", "ATTRIBUTES_48"})
    void benchmarkRotationsPreserveCompleteResults(final String scenario) {
        final var fixture = new CodegenBenchmark();
        fixture.scenario = scenario;
        fixture.setup();
        final int[][] dynamic = new int[32][];
        for (int i = 0; i < dynamic.length; i++) dynamic[i] = fixture.dynamic();
        for (int i = 0; i < dynamic.length; i++) assertArrayEquals(dynamic[i], fixture.generated());
    }
}
