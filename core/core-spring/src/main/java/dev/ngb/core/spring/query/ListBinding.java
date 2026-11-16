package dev.ngb.core.spring.query;

import dev.ngb.core.query.Filter;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Op;
import dev.ngb.core.query.SortKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/**
 * Ties the fields of a {@link FilterSchema} to jOOQ columns and compiles a {@link ListQuery} to conditions and an
 * ordering. Every value is a bind variable; no request text reaches the SQL.
 *
 * <p>A field of the resource binds to one of its own columns. A related field ({@code wallet.customer_id}) binds to a
 * column of the related table together with a function that wraps the predicate on that column into an
 * {@code exists} subquery, so a one-to-many relation never multiplies the rows of the listing.
 */
public final class ListBinding {

    private static final char ESCAPE = '\\';

    private record Bound(Field<Object> column, UnaryOperator<Condition> wrap) {}

    private final FilterSchema schema;
    private final Map<String, Bound> bound;

    private ListBinding(FilterSchema schema, Map<String, Bound> bound) {
        this.schema = schema;
        this.bound = bound;
    }

    public static Builder of(FilterSchema schema) {
        return new Builder(schema);
    }

    public static final class Builder {
        private final FilterSchema schema;
        private final Map<String, Bound> bound = new HashMap<>();

        private Builder(FilterSchema schema) {
            this.schema = schema;
        }

        /** Binds a field of the resource to one of its columns. */
        public Builder column(String field, Field<?> column) {
            return put(field, column, UnaryOperator.identity());
        }

        /**
         * Binds a related field to a column of the related table.
         *
         * @param exists wraps a condition on {@code column} into {@code exists (select 1 from related where link and cond)}
         */
        public Builder related(String field, Field<?> column, UnaryOperator<Condition> exists) {
            return put(field, column, exists);
        }

        @SuppressWarnings("unchecked")
        private Builder put(String field, Field<?> column, UnaryOperator<Condition> wrap) {
            if (bound.put(field, new Bound((Field<Object>) column, wrap)) != null) {
                throw new IllegalArgumentException("the field " + field + " is bound twice");
            }
            return this;
        }

        /**
         * @throws IllegalStateException when a schema field has no binding, a binding names no schema field, or a
         *     column's nullability differs from the field's, which would break keyset paging
         */
        public ListBinding build() {
            Set<String> declared = new HashSet<>();
            schema.fields().forEach(spec -> declared.add(spec.name()));
            Set<String> missing = new HashSet<>(declared);
            missing.removeAll(bound.keySet());
            Set<String> extra = new HashSet<>(bound.keySet());
            extra.removeAll(declared);
            if (!missing.isEmpty() || !extra.isEmpty()) {
                throw new IllegalStateException("the binding and the schema differ: unbound " + missing + ", unknown " + extra);
            }
            for (FieldSpec spec : schema.fields()) {
                Field<Object> column = bound.get(spec.name()).column();
                if (column.getDataType().nullable() != spec.nullable()) {
                    throw new IllegalStateException("the field " + spec.name() + " is declared "
                            + (spec.nullable() ? "nullable" : "not nullable") + " but its column " + column.getName() + " is "
                            + (column.getDataType().nullable() ? "nullable" : "NOT NULL"));
                }
            }
            return new ListBinding(schema, Map.copyOf(bound));
        }
    }

    public FilterSchema schema() {
        return schema;
    }

    /** @return the column a field is bound to */
    public Field<Object> column(String field) {
        return bound.get(field).column();
    }

    /** @return the condition for the filters of {@code query}: all of {@code all}, and one of the OR branches if any */
    public Condition where(ListQuery query) {
        Condition result = DSL.and(query.all().stream().map(this::compile).toList());
        if (!query.anyOf().isEmpty()) {
            List<Condition> branches = new ArrayList<>();
            for (List<Filter> branch : query.anyOf()) {
                branches.add(DSL.and(branch.stream().map(this::compile).toList()));
            }
            result = result.and(DSL.or(branches));
        }
        return result;
    }

    /** @return the ordering of {@code query}, tie-breaker included */
    public List<SortField<?>> order(ListQuery query) {
        List<SortField<?>> order = new ArrayList<>();
        for (SortKey key : query.sort()) {
            Field<Object> column = column(key.field().name());
            order.add(key.descending() ? column.desc() : column.asc());
        }
        return order;
    }

    /**
     * The rows after the cursor, in the order of {@code query}: for keys {@code k1..kn} with last values {@code v1..vn}
     * the row must satisfy {@code k1 > v1 or (k1 = v1 and (k2 > v2 or ...))}, with {@code <} for a descending key. Written
     * out rather than delegated to a row-value comparison because the directions may differ per key.
     */
    public Condition after(ListQuery query) {
        List<SortKey> keys = query.sort();
        List<Object> values = query.cursorValues();
        Condition result = null;
        for (int i = keys.size() - 1; i >= 0; i--) {
            Field<Object> column = column(keys.get(i).field().name());
            Field<Object> value = bind(column, values.get(i));
            Condition beyond = keys.get(i).descending() ? column.lt(value) : column.gt(value);
            result = result == null ? beyond : beyond.or(column.eq(value).and(result));
        }
        return result;
    }

    /** @return the sort values of {@code row}, in the form a cursor stores, so the next page can start after it */
    public List<Object> sortValues(Record row, ListQuery query) {
        List<Object> values = new ArrayList<>();
        for (SortKey key : query.sort()) {
            values.add(queryValue(key.field().type(), row.get(column(key.field().name()))));
        }
        return values;
    }

    private Condition compile(Filter filter) {
        Bound target = bound.get(filter.field().name());
        return target.wrap().apply(predicate(target.column(), filter));
    }

    private static Condition predicate(Field<Object> column, Filter filter) {
        if (filter.op() == Op.IS_NULL) {
            return Boolean.TRUE.equals(filter.value()) ? column.isNull() : column.isNotNull();
        }
        List<Field<Object>> values = filter.values().stream().map(v -> bind(column, v)).toList();
        return switch (filter.op()) {
            case EQ -> column.eq(values.get(0));
            case NEQ -> column.ne(values.get(0));
            case IN -> column.in(values);
            case NIN -> column.notIn(values);
            case GT -> column.gt(values.get(0));
            case GTE -> column.ge(values.get(0));
            case LT -> column.lt(values.get(0));
            case LTE -> column.le(values.get(0));
            case IS_NULL -> throw new IllegalStateException("is_null is answered before a value is bound");
            case STARTS_WITH -> like(column, escape((String) filter.value()) + "%");
            case CONTAINS -> like(column, "%" + escape((String) filter.value()) + "%");
        };
    }

    /** A bind variable typed by the column, so jOOQ converts the request value to what the column stores. */
    private static Field<Object> bind(Field<Object> column, Object value) {
        return DSL.val(value, column);
    }

    @SuppressWarnings("unchecked")
    private static Condition like(Field<Object> column, String pattern) {
        return ((Field<String>) (Field<?>) column).likeIgnoreCase(pattern, ESCAPE);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Object queryValue(FieldType type, Object raw) {
        return switch (type) {
            case INSTANT -> raw instanceof OffsetDateTime at ? at.toInstant()
                    : raw instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC) : (Instant) raw;
            case LONG -> ((Number) raw).longValue();
            case DECIMAL -> raw instanceof BigDecimal decimal ? decimal : new BigDecimal(raw.toString());
            case UUID -> (UUID) raw;
            case BOOLEAN -> (Boolean) raw;
            case STRING, ENUM -> raw.toString();
        };
    }
}
