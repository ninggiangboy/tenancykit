package dev.ngb.core.query;

import java.util.Arrays;
import java.util.Optional;

/** The comparison a filter predicate applies; the wire name is the segment in {@code filter[field][op]}. */
public enum Op {
    EQ("eq"),
    NEQ("neq"),
    IN("in"),
    NIN("nin"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IS_NULL("is_null"),
    STARTS_WITH("starts_with"),
    CONTAINS("contains");

    private final String wire;

    Op(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** @return the operator whose wire name is {@code wire}, empty when there is none */
    public static Optional<Op> ofWire(String wire) {
        return Arrays.stream(values()).filter(op -> op.wire.equals(wire)).findFirst();
    }

    /** @return true when the operator takes a list of values */
    public boolean multiValued() {
        return this == IN || this == NIN;
    }

    /** @return true when the operator orders its operand rather than only comparing it for equality */
    public boolean ordering() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }
}
