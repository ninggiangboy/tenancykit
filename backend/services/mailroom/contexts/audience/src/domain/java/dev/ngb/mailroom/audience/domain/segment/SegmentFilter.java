package dev.ngb.mailroom.audience.domain.segment;

import dev.ngb.core.error.BadRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed filter a segment stores: a small JSON AST over receiver attributes and three built-in fields.
 *
 * <p>The operator set is closed. A filter is validated at write time and again before evaluation, and the adapter
 * compiles it to parameterised SQL; no expression text is ever evaluated or concatenated. Depth and predicate count
 * are capped so a pathological filter cannot turn into a query plan the database cannot finish.
 */
public final class SegmentFilter {

    public static final int MAX_DEPTH = 4;
    public static final int MAX_PREDICATES = 20;
    public static final int MAX_IN_VALUES = 100;

    private static final Pattern FIELD = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private static final Set<String> COMPOSITE = Set.of("and", "or", "not");
    private static final Set<String> PREDICATE_OPS =
            Set.of("eq", "neq", "in", "gt", "gte", "lt", "lte", "exists", "in_group");
    private static final Set<String> BUILT_IN = Set.of("email_domain", "created_at", "status", "group");

    private SegmentFilter() {}

    public static Map<String, Object> validate(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            throw new BadRequestException("a segment needs a filter");
        }
        int[] predicates = {0};
        validateNode(filter, 1, predicates);
        return filter;
    }

    private static void validateNode(Map<String, Object> node, int depth, int[] predicates) {
        if (depth > MAX_DEPTH) {
            throw new BadRequestException("a filter nests at most " + MAX_DEPTH + " levels");
        }
        if (node.containsKey("and")) {
            children(node.get("and"), depth, predicates);
            return;
        }
        if (node.containsKey("or")) {
            children(node.get("or"), depth, predicates);
            return;
        }
        if (node.containsKey("not")) {
            Map<String, Object> child = asObject(node.get("not"), "not");
            validateNode(child, depth + 1, predicates);
            return;
        }
        predicates[0]++;
        if (predicates[0] > MAX_PREDICATES) {
            throw new BadRequestException("a filter has at most " + MAX_PREDICATES + " predicates");
        }
        predicate(node);
    }

    private static void children(Object value, int depth, int[] predicates) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new BadRequestException("and/or holds a non-empty list of filters");
        }
        for (Object child : list) {
            validateNode(asObject(child, "and/or"), depth + 1, predicates);
        }
    }

    private static void predicate(Map<String, Object> node) {
        Object fieldValue = node.get("field");
        Object opValue = node.get("op");
        if (!(fieldValue instanceof String field) || !(opValue instanceof String op)) {
            throw new BadRequestException("a predicate names a field and an operator");
        }
        if (!PREDICATE_OPS.contains(op)) {
            throw new BadRequestException("the operator " + op + " is not supported");
        }
        if (!BUILT_IN.contains(field) && !FIELD.matcher(field).matches()) {
            throw new BadRequestException("the field " + field + " is not a valid receiver attribute");
        }
        if (op.equals("in_group")) {
            if (!field.equals("group")) {
                throw new BadRequestException("in_group applies to the built-in group field");
            }
            if (!(node.get("value") instanceof String id) || !isUuid(id)) {
                throw new BadRequestException("in_group takes a group id");
            }
            return;
        }
        if (op.equals("exists")) {
            Object value = node.get("value");
            if (value != null && !(value instanceof Boolean)) {
                throw new BadRequestException("exists takes a boolean value");
            }
            return;
        }
        Object value = node.get("value");
        if (op.equals("in")) {
            if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_IN_VALUES) {
                throw new BadRequestException("in takes a non-empty list of at most " + MAX_IN_VALUES + " values");
            }
            list.forEach(item -> scalar(field, item));
            return;
        }
        if (op.equals("gt") || op.equals("gte") || op.equals("lt") || op.equals("lte")) {
            if (field.equals("created_at")) {
                if (!(value instanceof String text) || !isInstant(text)) {
                    throw new BadRequestException("created_at compares against an ISO-8601 instant");
                }
                return;
            }
            // Only a receiver attribute may be ordered, and only as a number. The compiler can compare a built-in text
            // field such as email_domain only for equality, so refusing it here keeps a stored filter from failing at
            // evaluation time — which, for a campaign, means failing every scheduled campaign of the tenant.
            if (BUILT_IN.contains(field)) {
                throw new BadRequestException("the operator " + op + " orders a numeric attribute, not " + field);
            }
            if (!(value instanceof Number)) {
                throw new BadRequestException("the operator " + op + " compares a number");
            }
            return;
        }
        scalar(field, value);
    }

    private static void scalar(String field, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new BadRequestException("the field " + field + " compares against a scalar");
        }
    }

    private static Map<String, Object> asObject(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new BadRequestException(where + " holds a filter object");
    }

    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
