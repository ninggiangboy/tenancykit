package dev.ngb.core.query;

import java.util.List;

/**
 * One validated predicate: a field, an operator and the typed operands. The values are {@link String}, {@link java.util.UUID},
 * {@link Long}, {@link java.math.BigDecimal}, {@link Boolean} or {@link java.time.Instant} according to the field's type;
 * {@link Op#IS_NULL} carries one {@link Boolean}.
 */
public record Filter(FieldSpec field, Op op, List<Object> values) {

    public Filter {
        values = List.copyOf(values);
    }

    /** @return the single operand of an operator that takes exactly one */
    public Object value() {
        return values.get(0);
    }
}
