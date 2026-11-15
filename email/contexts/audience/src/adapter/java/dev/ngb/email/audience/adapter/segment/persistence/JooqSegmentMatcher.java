package dev.ngb.email.audience.adapter.segment.persistence;

import static dev.ngb.email_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.email_jooq.Tables.RECEIVERS;
import static dev.ngb.email_jooq.Tables.SUPPRESSIONS;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import dev.ngb.email.audience.api.audience.ReceiverSummary;
import dev.ngb.email.audience.application.segment.port.SegmentMatcher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compiles a validated filter AST into parameterised jOOQ conditions.
 *
 * <p>An attribute name and an attribute value always become bind parameters; only the JSONB operators and the
 * built-in column references are literal. The base predicate is not optional: it always adds the tenant, a
 * {@code SUBSCRIBED} status and an anti-join against suppressions, so a segment can never return another tenant's
 * receiver or an address that withdrew consent.
 */
public class JooqSegmentMatcher implements SegmentMatcher {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqSegmentMatcher(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public long count(TenantId tenantId, Map<String, Object> filter) {
        return dsl.fetchCount(RECEIVERS, base(tenantId, filter));
    }

    @Override
    public List<ReceiverSummary> slice(
            TenantId tenantId, Map<String, Object> filter, UUID afterReceiverId, int limit) {
        Condition where = base(tenantId, filter);
        if (afterReceiverId != null) {
            where = where.and(RECEIVERS.ID.gt(afterReceiverId));
        }
        return dsl.select(
                        RECEIVERS.ID, RECEIVERS.EMAIL, RECEIVERS.FIRST_NAME, RECEIVERS.LAST_NAME, RECEIVERS.ATTRIBUTES)
                .from(RECEIVERS)
                .where(where)
                .orderBy(RECEIVERS.ID.asc())
                .limit(limit)
                .fetch(this::toSummary);
    }

    private ReceiverSummary toSummary(Record row) {
        return new ReceiverSummary(
                row.get(RECEIVERS.ID),
                row.get(RECEIVERS.EMAIL),
                row.get(RECEIVERS.FIRST_NAME),
                row.get(RECEIVERS.LAST_NAME),
                json.readValue(row.get(RECEIVERS.ATTRIBUTES).data(), ATTRIBUTES));
    }

    private Condition base(TenantId tenantId, Map<String, Object> filter) {
        Condition marketing = RECEIVERS.STATUS.eq("SUBSCRIBED")
                .and(DSL.notExists(DSL.selectOne()
                        .from(SUPPRESSIONS)
                        .where(SUPPRESSIONS.TENANT_ID.eq(tenantId.value()))
                        .and(SUPPRESSIONS.EMAIL_NORMALIZED.eq(RECEIVERS.EMAIL_NORMALIZED))));
        return RECEIVERS.TENANT_ID.eq(tenantId.value()).and(marketing).and(compile(filter));
    }

    private Condition compile(Map<String, Object> node) {
        if (node.containsKey("and")) {
            return DSL.and(children(node.get("and")));
        }
        if (node.containsKey("or")) {
            return DSL.or(children(node.get("or")));
        }
        if (node.containsKey("not")) {
            return DSL.not(compile(asMap(node.get("not"))));
        }
        return predicate(node);
    }

    private List<Condition> children(Object value) {
        List<Condition> conditions = new ArrayList<>();
        for (Object child : (List<?>) value) {
            conditions.add(compile(asMap(child)));
        }
        return conditions;
    }

    private Condition predicate(Map<String, Object> node) {
        String field = String.valueOf(node.get("field"));
        String op = String.valueOf(node.get("op"));
        Object value = node.get("value");

        if (op.equals("in_group")) {
            UUID groupId = UUID.fromString(String.valueOf(value));
            return RECEIVERS.ID.in(DSL.select(GROUP_MEMBERS.RECEIVER_ID)
                    .from(GROUP_MEMBERS)
                    .where(GROUP_MEMBERS.GROUP_ID.eq(groupId)));
        }
        return switch (field) {
            case "status" -> text(RECEIVERS.STATUS, op, value);
            case "email_domain" -> text(
                    DSL.field("split_part({0}, '@', 2)", String.class, RECEIVERS.EMAIL), op, value);
            case "created_at" -> instant(RECEIVERS.CREATED_AT, op, value);
            default -> attribute(field, op, value);
        };
    }

    /**
     * Compiles one attribute predicate.
     *
     * <p>Equality and membership compare JSONB rather than a cast text: {@code attributes @> jsonb_build_object(key,
     * to_jsonb(value))} is what the {@code jsonb_path_ops} GIN index answers, and it is type-exact, so a receiver whose
     * {@code age} is the string {@code "unknown"} is simply not a numeric match instead of aborting the query.
     *
     * <p>Ordering needs a number, so the cast is guarded by {@code jsonb_typeof(attributes -> key) = 'number'}: a
     * {@code CASE} that yields null for every other row. One receiver with a mixed-type attribute can therefore never
     * make the whole segment — a preview or a campaign's snapshot — fail.
     */
    private Condition attribute(String field, String op, Object value) {
        if (op.equals("exists")) {
            Condition present = DSL.condition("jsonb_exists({0}, {1})", RECEIVERS.ATTRIBUTES, DSL.val(field));
            return Boolean.FALSE.equals(value) ? DSL.not(present) : present;
        }
        if (op.equals("eq")) {
            return contains(field, value);
        }
        if (op.equals("in")) {
            return ((List<?>) value).stream()
                    .map(item -> contains(field, item))
                    .reduce(DSL.falseCondition(), Condition::or);
        }
        if (op.equals("neq")) {
            return DSL.condition("jsonb_exists({0}, {1})", RECEIVERS.ATTRIBUTES, DSL.val(field))
                    .and(DSL.not(contains(field, value)));
        }
        Field<BigDecimal> number = DSL.field(
                "case when jsonb_typeof({0} -> {1}) = 'number' then ({0} ->> {1})::numeric end",
                BigDecimal.class,
                RECEIVERS.ATTRIBUTES,
                DSL.val(field));
        return switch (op) {
            case "gt", "gte", "lt", "lte" -> compare(number, op, new BigDecimal(String.valueOf(value)));
            default -> throw new BadRequestException("the operator " + op + " does not compare an attribute");
        };
    }

    /** {@code attributes @> {"field": value}}, the containment the GIN index answers. */
    private static Condition contains(String field, Object value) {
        return DSL.condition(
                "{0} @> jsonb_build_object({1}::text, to_jsonb({2}))",
                RECEIVERS.ATTRIBUTES,
                DSL.val(field),
                DSL.val(value));
    }

    private static Condition text(Field<String> field, String op, Object value) {
        return switch (op) {
            case "eq" -> field.eq(String.valueOf(value));
            case "neq" -> field.ne(String.valueOf(value));
            case "in" -> field.in(((List<?>) value).stream().map(String::valueOf).toList());
            case "exists" -> Boolean.FALSE.equals(value) ? field.isNull() : field.isNotNull();
            default -> throw new BadRequestException("the operator " + op + " does not compare text");
        };
    }

    private static Condition compare(Field<BigDecimal> field, String op, BigDecimal value) {
        return switch (op) {
            case "eq" -> field.eq(value);
            case "neq" -> field.ne(value);
            case "gt" -> field.gt(value);
            case "gte" -> field.ge(value);
            case "lt" -> field.lt(value);
            case "lte" -> field.le(value);
            default -> throw new BadRequestException("the operator " + op + " does not compare numbers");
        };
    }

    private static Condition instant(Field<Instant> field, String op, Object value) {
        Instant instant = Instant.parse(String.valueOf(value));
        return switch (op) {
            case "eq" -> field.eq(instant);
            case "neq" -> field.ne(instant);
            case "gt" -> field.gt(instant);
            case "gte" -> field.ge(instant);
            case "lt" -> field.lt(instant);
            case "lte" -> field.le(instant);
            default -> throw new BadRequestException("the operator " + op + " does not compare an instant");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
