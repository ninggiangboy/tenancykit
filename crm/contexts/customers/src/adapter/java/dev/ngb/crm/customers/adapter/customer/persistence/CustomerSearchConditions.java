package dev.ngb.crm.customers.adapter.customer.persistence;

import static dev.ngb.crm_jooq.Tables.CONSENT_EVENTS;
import static dev.ngb.crm_jooq.Tables.CONSENT_TOPICS;
import static dev.ngb.crm_jooq.Tables.CUSTOMERS;
import static dev.ngb.crm_jooq.Tables.CUSTOMER_TAGS;
import static dev.ngb.crm_jooq.Tables.FIELD_DEFINITIONS;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.crm.customers.application.customer.query.CustomerSearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

/**
 * Turns what a caller narrowed the customer list by into the predicates the query runs.
 *
 * <p>Every predicate is written so an index serves it: the search term matches the reference by its unique key, the
 * phone by a range on the digit column, the email and the name through the trigram indexes, a tag through the tag
 * table's own indexes, a field value through the GIN index on the stored object, and a consent through the topic's
 * latest record. The values come from the Tenant's own vocabulary, so a field key or a topic the Tenant never defined
 * is refused rather than silently ignored.
 */
public final class CustomerSearchConditions {

    private static final char ESCAPE = '\\';

    private static final String GRANTED = "granted";

    private static final String REVOKED = "revoked";

    private static final String NONE = "none";

    private final DSLContext dsl;

    public CustomerSearchConditions(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Every predicate the caller asked for, joined with AND. An absent criterion narrows nothing. */
    public Condition of(CustomerSearchCriteria criteria) {
        if (criteria == null) {
            return DSL.noCondition();
        }
        Condition condition = DSL.noCondition();
        if (criteria.q() != null && !criteria.q().isBlank()) {
            condition = condition.and(term(criteria.q().strip()));
        }
        for (String tag : criteria.tags()) {
            condition = condition.and(tagged(tag == null ? "" : tag.strip()));
        }
        if (!criteria.fieldEquals().isEmpty()
                || !criteria.fieldAtLeast().isEmpty()
                || !criteria.fieldAtMost().isEmpty()) {
            Map<String, String> types = fieldTypes();
            for (Map.Entry<String, String> wanted : criteria.fieldEquals().entrySet()) {
                condition = condition.and(fieldIs(wanted.getKey(), wanted.getValue(), types));
            }
            for (Map.Entry<String, String> wanted : criteria.fieldAtLeast().entrySet()) {
                condition = condition.and(fieldCompared(wanted.getKey(), wanted.getValue(), types, true));
            }
            for (Map.Entry<String, String> wanted : criteria.fieldAtMost().entrySet()) {
                condition = condition.and(fieldCompared(wanted.getKey(), wanted.getValue(), types, false));
            }
        }
        if (!criteria.consents().isEmpty()) {
            Set<String> topics = topicKeys();
            for (Map.Entry<String, String> wanted : criteria.consents().entrySet()) {
                condition = condition.and(consent(wanted.getKey(), wanted.getValue(), topics));
            }
        }
        if (criteria.createdFrom() != null) {
            condition = condition.and(CUSTOMERS.CREATED_AT.ge(criteria.createdFrom()));
        }
        if (criteria.createdTo() != null) {
            condition = condition.and(CUSTOMERS.CREATED_AT.le(criteria.createdTo()));
        }
        return condition;
    }

    /**
     * A search term: the reference exactly, a phone-digit prefix, an email prefix or part of a name. The three fuzzy
     * arms are what the trigram indexes are for, and the exact one is the unique key.
     */
    private static Condition term(String raw) {
        String lowered = raw.toLowerCase(Locale.ROOT);
        Condition match = CUSTOMERS.EXTERNAL_REF.eq(raw);
        String digits = digits(raw);
        if (!digits.isEmpty()) {
            // A prefix of the digits, as a range: the btree on the digit column answers it whatever the collation.
            match = match.or(CUSTOMERS.PHONE_DIGITS.ge(digits).and(CUSTOMERS.PHONE_DIGITS.lt(digits + ":")));
        }
        match = match.or(DSL.lower(CUSTOMERS.EMAIL).like(escape(lowered) + "%", ESCAPE));
        match = match.or(DSL.lower(CUSTOMERS.DISPLAY_NAME).like("%" + escape(lowered) + "%", ESCAPE));
        return match;
    }

    private static String digits(String term) {
        StringBuilder digits = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    private static String escape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A tag the customer must carry: an existence check against the tag table, so the listing never multiplies rows. */
    private static Condition tagged(String tag) {
        return DSL.exists(DSL.selectOne()
                .from(CUSTOMER_TAGS)
                .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(CUSTOMERS.ID))
                .and(CUSTOMER_TAGS.TAG.eq(tag)));
    }

    /** The key and type of every field definition the Tenant has; an archived one still has stored values. */
    private Map<String, String> fieldTypes() {
        return dsl.select(FIELD_DEFINITIONS.KEY, FIELD_DEFINITIONS.TYPE)
                .from(FIELD_DEFINITIONS)
                .fetchMap(FIELD_DEFINITIONS.KEY, FIELD_DEFINITIONS.TYPE);
    }

    private Set<String> topicKeys() {
        return new HashSet<>(dsl.select(CONSENT_TOPICS.KEY).from(CONSENT_TOPICS).fetch(CONSENT_TOPICS.KEY));
    }

    /** Equality on a stored field value: containment, which is what the GIN index on the object serves. */
    private Condition fieldIs(String key, String raw, Map<String, String> types) {
        String type = typeOf(key, types);
        Object value = stored(key, raw == null ? "" : raw.strip(), type);
        return CUSTOMERS.FIELDS.contains(JSONB.valueOf("{\"" + key + "\":" + json(value) + "}"));
    }

    /**
     * A bound on a {@code NUMBER} or {@code DATE} field: the comparison runs on the value read out of the object, so a
     * range lists the same rows the values themselves would.
     */
    private Condition fieldCompared(String key, String raw, Map<String, String> types, boolean atLeast) {
        String type = typeOf(key, types);
        String text = raw == null ? "" : raw.strip();
        if (type.equals("NUMBER")) {
            BigDecimal bound = number(text);
            Field<BigDecimal> numeric =
                    DSL.field("{0} ->> {1}", BigDecimal.class, CUSTOMERS.FIELDS, DSL.val(key));
            return atLeast ? numeric.ge(bound) : numeric.le(bound);
        }
        if (type.equals("DATE")) {
            // A date is stored as its ISO text, so the text order is the date order.
            String date = date(text);
            Field<String> value = DSL.field("{0} ->> {1}", String.class, CUSTOMERS.FIELDS, DSL.val(key));
            return atLeast ? value.ge(date) : value.le(date);
        }
        throw new BadRequestException("The field " + key + " takes a bound only when it is a NUMBER or a DATE.");
    }

    private static String typeOf(String key, Map<String, String> types) {
        String type = types.get(key);
        if (type == null) {
            throw new BadRequestException("The field " + key + " is not a field of this tenant.");
        }
        return type;
    }

    /** The value as the definition's type stores it, so a filter compares against the stored form. */
    private static Object stored(String key, String raw, String type) {
        return switch (type) {
            case "NUMBER" -> number(raw);
            case "DATE" -> date(raw);
            case "BOOLEAN" -> {
                if (!raw.equals("true") && !raw.equals("false")) {
                    throw new BadRequestException("The field " + key + " takes true or false.");
                }
                yield Boolean.parseBoolean(raw);
            }
            default -> raw;
        };
    }

    private static BigDecimal number(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException notANumber) {
            throw new BadRequestException("A NUMBER field takes a number.");
        }
    }

    private static String date(String raw) {
        try {
            return LocalDate.parse(raw).toString();
        } catch (DateTimeParseException notADate) {
            throw new BadRequestException("A DATE field takes a date as text, yyyy-MM-dd.");
        }
    }

    /**
     * A consent topic's current state. {@code granted} and {@code revoked} read the latest record of that topic —
     * ordered by what happened, with the id breaking a tie — and {@code none} is a customer with no record at all.
     */
    private static Condition consent(String topic, String state, Set<String> topics) {
        if (!topics.contains(topic)) {
            throw new BadRequestException("The topic " + topic + " is not a consent topic of this tenant.");
        }
        String wanted = state == null ? "" : state.strip().toLowerCase(Locale.ROOT);
        return switch (wanted) {
            case GRANTED -> latestRecordOf(topic, "GRANTED");
            case REVOKED -> latestRecordOf(topic, "REVOKED");
            case NONE -> DSL.notExists(DSL.selectOne()
                    .from(CONSENT_EVENTS)
                    .where(CONSENT_EVENTS.CUSTOMER_ID.eq(CUSTOMERS.ID))
                    .and(CONSENT_EVENTS.TOPIC_KEY.eq(topic)));
            default -> throw new BadRequestException("A consent filter takes granted, revoked or none.");
        };
    }

    /** One row per customer: the topic's latest record, linked to the customer the listing is walking. */
    private static Condition latestRecordOf(String topic, String status) {
        var latest = DSL.select(CONSENT_EVENTS.CUSTOMER_ID, CONSENT_EVENTS.STATUS)
                .distinctOn(CONSENT_EVENTS.CUSTOMER_ID)
                .from(CONSENT_EVENTS)
                .where(CONSENT_EVENTS.TOPIC_KEY.eq(topic))
                .orderBy(CONSENT_EVENTS.CUSTOMER_ID, CONSENT_EVENTS.OCCURRED_AT.desc(), CONSENT_EVENTS.ID.desc())
                .asTable("latest_consent");
        Field<java.util.UUID> customerId = latest.field(CONSENT_EVENTS.CUSTOMER_ID);
        Field<String> recorded = latest.field(CONSENT_EVENTS.STATUS);
        return DSL.exists(DSL.selectOne()
                .from(latest)
                .where(customerId.eq(CUSTOMERS.ID))
                .and(recorded.eq(status)));
    }

    /** A value as it appears inside the stored JSON object. */
    private static String json(Object value) {
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
