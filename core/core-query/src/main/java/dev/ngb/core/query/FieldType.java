package dev.ngb.core.query;

import dev.ngb.core.error.BadRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** The value kinds a filter can compare. Each knows its default operators and how to read and print a value. */
public enum FieldType {
    STRING(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN, Op.STARTS_WITH)),
    UUID(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN)),
    ENUM(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN)),
    LONG(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN, Op.GT, Op.GTE, Op.LT, Op.LTE)),
    DECIMAL(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN, Op.GT, Op.GTE, Op.LT, Op.LTE)),
    BOOLEAN(EnumSet.of(Op.EQ, Op.NEQ)),
    INSTANT(EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NIN, Op.GT, Op.GTE, Op.LT, Op.LTE));

    /** The longest text value a request may compare against, so a request cannot ship a megabyte into a query. */
    public static final int MAX_TEXT = 255;

    private final Set<Op> defaultOps;

    FieldType(Set<Op> defaultOps) {
        this.defaultOps = defaultOps;
    }

    /** @return the operators a field of this type gets before it adds any */
    public Set<Op> defaultOps() {
        return EnumSet.copyOf(defaultOps);
    }

    /** @return true when the operator can ever apply to this type */
    public boolean supports(Op op) {
        return switch (op) {
            case EQ, NEQ, IN, NIN, IS_NULL -> true;
            case GT, GTE, LT, LTE -> this == LONG || this == DECIMAL || this == INSTANT;
            case STARTS_WITH, CONTAINS -> this == STRING;
        };
    }

    /** @return the typed value for {@code raw}; a value that does not fit the type is a 400 that names the field */
    Object parse(FieldSpec field, String raw) {
        try {
            return switch (this) {
                case STRING -> text(raw);
                case UUID -> java.util.UUID.fromString(raw);
                case ENUM -> {
                    if (!field.values().contains(raw)) {
                        throw new BadRequestException("the field " + field.name() + " takes one of " + field.values()
                                + ", not " + shorten(raw));
                    }
                    yield raw;
                }
                case LONG -> Long.parseLong(raw);
                case DECIMAL -> new BigDecimal(raw);
                case BOOLEAN -> bool(raw);
                case INSTANT -> Instant.parse(raw);
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            if (e instanceof BadRequestException bad) {
                throw bad;
            }
            throw new BadRequestException("the field " + field.name() + " takes " + describe() + ", not " + shorten(raw));
        }
    }

    /** @return the canonical text of a typed value, the form a cursor stores and a hash is built from */
    String format(Object value) {
        return switch (this) {
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            default -> value.toString();
        };
    }

    private static String text(String raw) {
        if (raw.length() > MAX_TEXT) {
            throw new BadRequestException("a text value is at most " + MAX_TEXT + " characters");
        }
        return raw;
    }

    private static Boolean bool(String raw) {
        if (raw.equals("true")) {
            return Boolean.TRUE;
        }
        if (raw.equals("false")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("not a boolean");
    }

    private String describe() {
        return switch (this) {
            case STRING -> "text";
            case UUID -> "a UUID";
            case ENUM -> "one of its values";
            case LONG -> "a whole number";
            case DECIMAL -> "a decimal number";
            case BOOLEAN -> "true or false";
            case INSTANT -> "an ISO-8601 instant such as 2026-11-16T10:15:30Z";
        };
    }

    private static String shorten(String raw) {
        return raw.length() <= 40 ? "'" + raw + "'" : "'" + raw.substring(0, 40) + "...'";
    }
}
