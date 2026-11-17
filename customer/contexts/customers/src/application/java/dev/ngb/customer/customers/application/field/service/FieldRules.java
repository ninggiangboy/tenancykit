package dev.ngb.customer.customers.application.field.service;

import dev.ngb.customer.customers.application.field.exception.InvalidFieldException;
import dev.ngb.customer.customers.domain.field.FieldDefinition;
import dev.ngb.customer.customers.domain.field.FieldType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a field definition, and a value written under one, must be before it is stored. The rules live in one place, so
 * the definition endpoints and the three customer write paths cannot disagree about what a value may be.
 *
 * <p>A value is normalised on the way in — a number to a decimal, a date to its ISO text — so the same value always
 * serialises to the same JSON, and a body that repeats what is stored changes nothing.
 */
public final class FieldRules {

    /** The longest key a Tenant may choose, counting its first character. */
    public static final int MAX_KEY = 40;

    public static final int MAX_LABEL = 200;

    /** The most choices a {@code CHOICE} accepts. */
    public static final int MAX_CHOICES = 50;

    public static final int MAX_CHOICE = 100;

    /** The longest text value, a deployment-independent bound on one value. */
    public static final int MAX_TEXT = 2000;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private FieldRules() {}

    /** The key: lower snake case, starting with a letter, unique per tenant and immutable. */
    public static String key(String raw) {
        if (raw == null) {
            throw new InvalidFieldException("A key is required.");
        }
        String key = raw;
        if (!KEY.matcher(key).matches()) {
            throw new InvalidFieldException("A key is 1 to " + MAX_KEY + " lower-case letters, digits or underscores"
                    + " and starts with a letter.");
        }
        return key;
    }

    /** The label the Tenant shows for the definition. */
    public static String label(String raw) {
        if (raw == null) {
            throw new InvalidFieldException("A label is required.");
        }
        String label = raw.strip();
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            throw new InvalidFieldException("A label is 1 to " + MAX_LABEL + " characters.");
        }
        return label;
    }

    /** The type, as the definition request names it. */
    public static FieldType type(String raw) {
        if (raw == null) {
            throw new InvalidFieldException("A type is required.");
        }
        try {
            return FieldType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidFieldException(
                    "A type is one of TEXT, NUMBER, DATE, BOOLEAN or CHOICE.");
        }
    }

    /** The choices of a new definition: exactly a {@code CHOICE} carries 1 to 50 of them, every other type none. */
    public static List<String> choices(FieldType type, List<String> raw) {
        List<String> choices = distinctChoices(raw);
        if (type == FieldType.CHOICE) {
            if (choices.isEmpty()) {
                throw new InvalidFieldException("A CHOICE field carries at least one value.");
            }
            return choices;
        }
        if (!choices.isEmpty()) {
            throw new InvalidFieldException("Only a CHOICE field carries values.");
        }
        return List.of();
    }

    /**
     * The choices a patch adds to an existing definition: only a {@code CHOICE} takes any, an existing one cannot be
     * added again, and the definition cannot grow past the cap.
     */
    public static List<String> addedChoices(FieldDefinition definition, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (definition.type() != FieldType.CHOICE) {
            throw new InvalidFieldException("Only a CHOICE field carries values.");
        }
        List<String> added = distinctChoices(raw);
        for (String choice : added) {
            if (definition.choices().contains(choice)) {
                throw new InvalidFieldException("A CHOICE field already accepts one of the values sent.");
            }
        }
        if (definition.choices().size() + added.size() > MAX_CHOICES) {
            throw new InvalidFieldException("A CHOICE field accepts at most " + MAX_CHOICES + " values.");
        }
        return added;
    }

    /**
     * The value a definition accepts, normalised, so what is stored is what the definition means.
     *
     * @throws InvalidFieldException when the value is of another type than the definition's
     */
    public static Object value(FieldDefinition definition, Object raw) {
        return switch (definition.type()) {
            case TEXT -> text(raw);
            case NUMBER -> number(raw);
            case DATE -> date(raw);
            case BOOLEAN -> bool(raw);
            case CHOICE -> choice(definition, raw);
        };
    }

    private static String text(Object raw) {
        if (!(raw instanceof String text)) {
            throw new InvalidFieldException("A TEXT field takes a text value.");
        }
        if (text.isBlank() || text.length() > MAX_TEXT) {
            throw new InvalidFieldException("A TEXT value is 1 to " + MAX_TEXT + " characters.");
        }
        return text;
    }

    private static BigDecimal number(Object raw) {
        if (!(raw instanceof Number number)) {
            throw new InvalidFieldException("A NUMBER field takes a number.");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(number.toString());
        } catch (NumberFormatException notANumber) {
            throw new InvalidFieldException("A NUMBER field takes a finite number.");
        }
        if (decimal.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return decimal.stripTrailingZeros();
    }

    private static String date(Object raw) {
        if (!(raw instanceof String text)) {
            throw new InvalidFieldException("A DATE field takes a date as text, yyyy-MM-dd.");
        }
        try {
            return LocalDate.parse(text.strip()).toString();
        } catch (DateTimeParseException notADate) {
            throw new InvalidFieldException("A DATE value is an ISO-8601 date, yyyy-MM-dd.");
        }
    }

    private static Boolean bool(Object raw) {
        if (!(raw instanceof Boolean value)) {
            throw new InvalidFieldException("A BOOLEAN field takes true or false.");
        }
        return value;
    }

    private static String choice(FieldDefinition definition, Object raw) {
        if (!(raw instanceof String text)) {
            throw new InvalidFieldException("A CHOICE field takes one of its values as text.");
        }
        for (String choice : definition.choices()) {
            if (choice.equals(text)) {
                return text;
            }
        }
        throw new InvalidFieldException("A CHOICE value is one of the values its field accepts.");
    }

    private static List<String> distinctChoices(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> choices = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                throw new InvalidFieldException("A choice is 1 to " + MAX_CHOICE + " characters.");
            }
            String choice = entry.strip();
            if (choice.isEmpty() || choice.length() > MAX_CHOICE) {
                throw new InvalidFieldException("A choice is 1 to " + MAX_CHOICE + " characters.");
            }
            if (!choices.add(choice)) {
                throw new InvalidFieldException("A choice is declared twice.");
            }
        }
        List<String> ordered = new ArrayList<>(choices);
        if (ordered.size() > MAX_CHOICES) {
            throw new InvalidFieldException("A CHOICE field accepts at most " + MAX_CHOICES + " values.");
        }
        return List.copyOf(ordered);
    }
}
