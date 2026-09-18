package demo;

import demo.generated.OrderCode;
import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.math.BigDecimal;

/** Run with only this consumer and fletch on the classpath; no generator or compiler. */
public final class Main {
    private static final XmlMapping<Definitions.Order> ORDER = OrderCode.mapping(Definitions.order());

    private Main() {}

    public static void main(final String[] args) {
        final var expected = new Definitions.Order("42", new BigDecimal("12.30"));
        final var actual = Xml.extract("<order id='42'><total>12.30</total></order>", ORDER);
        if (!expected.equals(actual)) throw new AssertionError(actual);
        try (var session = ORDER.openSession()) {
            if (!expected.equals(session.extract("<order id='42'><total>12.30</total></order>"))) {
                throw new AssertionError("Session result differs");
            }
        }
        System.out.println(actual);
    }
}
