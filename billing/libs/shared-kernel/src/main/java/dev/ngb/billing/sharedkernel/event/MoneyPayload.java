package dev.ngb.billing.sharedkernel.event;

import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Map;

/**
 * An amount as the integration contract writes it: whole minor units (cents, dong) and an ISO 4217 currency code.
 * The keys {@code minorUnits} and {@code currency} are declared once, here, so no event class can drift from the
 * schemas in {@code contracts/events}.
 *
 * <p>The wire shape is a map, not this record: an event payload is a {@code Map<String, Object>} at the outbox, so a
 * consumer reads the amount back as a map whether it arrived over AMQP or through a webhook.
 */
public record MoneyPayload(long minorUnits, String currency) {

    public static MoneyPayload of(Money money) {
        return new MoneyPayload(money.minorUnits(), money.currency().code());
    }

    /** The {@code {"minorUnits": n, "currency": "XXX"}} object an event carries. */
    public Map<String, Object> asMap() {
        return Map.of("minorUnits", minorUnits, "currency", currency);
    }

    public Money toMoney() {
        return Money.of(minorUnits, Currency.of(currency));
    }
}
