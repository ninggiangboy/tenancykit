package dev.ngb.billing.sharedkernel.event;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How every event payload is built, so the shape and the treatment of an absent field are one thing instead of one
 * per event class.
 *
 * <p>The one null policy is that <em>a null value is absent</em>: a field an event has nothing to say about is
 * omitted, never written as JSON {@code null}. The schemas under {@code contracts/events} list only the fields that
 * are always present as {@code required}, and a consumer reads an omitted optional field as null through
 * {@link EventPayload}'s optional readers.
 */
public final class MoneyEventPayload {

    private MoneyEventPayload() {}

    /** The {@code {"minorUnits": n, "currency": "XXX"}} amount every money-carrying event carries. */
    public static Map<String, Object> money(Money money) {
        return MoneyPayload.of(money).asMap();
    }

    /**
     * The payload of an event, from {@code key, value} pairs in the order the schema lists them. A null value is
     * omitted.
     *
     * @throws IllegalArgumentException when an odd number of arguments is passed
     */
    public static Map<String, Object> payload(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("a payload is built from key, value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
