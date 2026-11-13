package dev.ngb.billing.sharedkernel.event;

import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a field of an event payload. Every failure is one {@link MalformedEventException} naming the field and
 * the reason, so no consumer has to catch a {@code NullPointerException} or a {@code ClassCastException} to find
 * out that a payload was not what it claimed. The keys are part of the integration contract
 * ({@code contracts/events}).
 */
public final class EventPayload {

    private EventPayload() {}

    /** @throws MalformedEventException when the key is absent or does not hold text */
    public static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof String text && !text.isEmpty()) {
            return text;
        }
        throw new MalformedEventException("the payload has no text '" + key + "'");
    }

    /** An optional text field: absent reads as null, but a value that is not text is still malformed. */
    public static String optionalText(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isEmpty() ? null : text;
        }
        throw new MalformedEventException("the payload's '" + key + "' is not text");
    }

    /** @throws MalformedEventException when the key is absent or is not an id */
    public static UUID uuid(Map<String, Object> data, String key) {
        return uuidOf(key, text(data, key));
    }

    /** An optional id: absent reads as null, but a value that is not an id is still malformed. */
    public static UUID optionalUuid(Map<String, Object> data, String key) {
        String value = optionalText(data, key);
        return value == null ? null : uuidOf(key, value);
    }

    /** The {@code {"minorUnits": n, "currency": "XXX"}} object every money event carries. */
    public static Money money(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Map<?, ?> amount)) {
            throw new MalformedEventException("the payload has no '" + key + "' object");
        }
        if (!(amount.get("minorUnits") instanceof Number units)) {
            throw new MalformedEventException("the payload's '" + key + ".minorUnits' is not a number");
        }
        if (!(amount.get("currency") instanceof String code)) {
            throw new MalformedEventException("the payload's '" + key + ".currency' is not a currency code");
        }
        try {
            return Money.of(units.longValue(), Currency.of(code));
        } catch (IllegalArgumentException notMoney) {
            throw new MalformedEventException("the payload's '" + key + "' is not " + units + " of " + code, notMoney);
        }
    }

    private static UUID uuidOf(String key, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            throw new MalformedEventException("the payload's '" + key + "' is not an id: " + value, notAnId);
        }
    }
}
