package demo;

import io.github.robsonkades.fletch.Xml;
import io.github.robsonkades.fletch.XmlMapping;
import java.math.BigDecimal;

/** Mapping factories compiled before generation; no reference to a generated class. */
public final class Definitions {
    private Definitions() {}

    public record Order(String id, BigDecimal total) {}

    private static final class Draft {
        String id;
        BigDecimal total;
    }

    public static XmlMapping<Order> order() {
        return Xml.mapping(Draft::new)
                .attr("/order@id", (d, v) -> d.id = v.asString())
                .text("/order/total", (d, v) -> d.total = v.asDecimal())
                .build(d -> new Order(d.id, d.total));
    }

    public static XmlMapping<int[]> elements4() { return fields(4, false); }
    public static XmlMapping<int[]> elements48() { return fields(48, false); }
    public static XmlMapping<int[]> attributes48() { return fields(48, true); }

    private static XmlMapping<int[]> fields(final int count, final boolean attributes) {
        final var builder = Xml.mapping(() -> new int[count]);
        for (int i = 0; i < count; i++) {
            final int index = i;
            if (attributes) builder.attr("/r@f" + i, (d, v) -> d[index] = v.asInt());
            else builder.text("/r/f" + i, (d, v) -> d[index] = v.asInt());
        }
        return builder.build(d -> d);
    }
}
