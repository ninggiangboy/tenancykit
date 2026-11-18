package dev.ngb.core.query;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One whitelisted field of a resource: the name the API exposes, its type and what a caller may do with it.
 *
 * <p>A name with a dot ({@code wallet.customer_id}) is a field of one related table; it can be filtered but never
 * sorted. A field is sortable only when its column is {@code NOT NULL}, because a keyset cursor cannot position
 * itself on a null. {@code nullable} must therefore match the column; the binding check in core-spring compares the
 * two against the jOOQ table.
 *
 * @param name the API name, lower snake case with at most one dot
 * @param type the value kind
 * @param values the accepted values of an {@link FieldType#ENUM}, empty for every other type
 * @param ops the operators a caller may apply
 * @param sortable whether the field may appear in {@code sort}
 * @param nullable whether the column admits null; only then is {@link Op#IS_NULL} offered
 */
public record FieldSpec(String name, FieldType type, Set<String> values, Set<Op> ops, boolean sortable, boolean nullable) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,40}(\\.[a-z][a-z0-9_]{0,40})?$");

    public FieldSpec {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a field name is lower snake case with at most one dot: " + name);
        }
        values = Set.copyOf(values);
        ops = Set.copyOf(ops);
        if (type == FieldType.ENUM && values.isEmpty()) {
            throw new IllegalArgumentException("the enum field " + name + " declares no values");
        }
        if (type != FieldType.ENUM && !values.isEmpty()) {
            throw new IllegalArgumentException("only an enum field declares values: " + name);
        }
        for (Op op : ops) {
            if (op == Op.IS_NULL ? !nullable : !type.supports(op)) {
                throw new IllegalArgumentException("the operator " + op.wire() + " does not apply to field " + name);
            }
        }
        if (sortable && nullable) {
            throw new IllegalArgumentException("the nullable field " + name + " cannot be sortable");
        }
        if (sortable && name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("the related field " + name + " cannot be sortable");
        }
    }

    /** @return a field of {@code type} with its default operators, not sortable and not nullable */
    public static FieldSpec of(String name, FieldType type) {
        return new FieldSpec(name, type, Set.of(), type.defaultOps(), false, false);
    }

    /** @return an enum field that accepts the constants of {@code values} */
    public static FieldSpec enumOf(String name, Class<? extends Enum<?>> values) {
        Set<String> names = new LinkedHashSet<>();
        for (Enum<?> constant : values.getEnumConstants()) {
            names.add(constant.name());
        }
        return new FieldSpec(name, FieldType.ENUM, names, FieldType.ENUM.defaultOps(), false, false);
    }

    /** @return an enum field that accepts exactly {@code values} */
    public static FieldSpec enumOf(String name, String... values) {
        return new FieldSpec(name, FieldType.ENUM, Set.of(values), FieldType.ENUM.defaultOps(), false, false);
    }

    /** @return this field with {@code more} added to its operators */
    public FieldSpec with(Op... more) {
        Set<Op> all = ops.isEmpty() ? EnumSet.noneOf(Op.class) : EnumSet.copyOf(ops);
        all.addAll(Arrays.asList(more));
        return new FieldSpec(name, type, values, all, sortable, nullable);
    }

    /** @return this field restricted to exactly {@code only} */
    public FieldSpec only(Op... only) {
        return new FieldSpec(name, type, values, Set.copyOf(Arrays.asList(only)), sortable, nullable);
    }

    /** @return this field marked sortable; its column must be NOT NULL */
    public FieldSpec asSortable() {
        return new FieldSpec(name, type, values, ops, true, nullable);
    }

    /** @return this field marked nullable, which offers {@code is_null} */
    public FieldSpec asNullable() {
        Set<Op> all = EnumSet.copyOf(ops.isEmpty() ? Set.of(Op.IS_NULL) : ops);
        all.add(Op.IS_NULL);
        return new FieldSpec(name, type, values, all, sortable, true);
    }

    /** @return true when the field lives on a related table */
    public boolean related() {
        return name.indexOf('.') >= 0;
    }

    /** @return the related table's alias, the part of the name before the dot; empty for a field of the resource */
    public String relation() {
        int dot = name.indexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }
}
