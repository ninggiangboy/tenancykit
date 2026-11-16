package dev.ngb.billing.money.api.event;

import dev.ngb.billing.sharedkernel.event.EventPayload;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Map;
import java.util.UUID;

/**
 * What {@code charge.succeeded} carries, as the consumers of the money context read it. The payload keys are the
 * integration contract, so {@link #parse(Map)} is the only place they are named: a consumer that calls it either
 * has the whole event or one {@link MalformedEventException}.
 *
 * <p>{@code amount} is what the wallet paid; {@code grossAmount} is what the charge was worth before coupons and is
 * absent on an event written before coupons existed, in which case the two are equal.
 */
public record ChargeSucceededPayload(UUID chargeId, WalletId walletId, Money amount, Money grossAmount, String source, String reference) {

    /**
     * @throws MalformedEventException when the payload is not a {@code charge.succeeded} payload
     */
    public static ChargeSucceededPayload parse(Map<String, Object> data) {
        Money amount = EventPayload.money(data, "amount");
        Money gross = EventPayload.optionalMoney(data, "grossAmount").orElse(amount);
        return new ChargeSucceededPayload(
                EventPayload.uuid(data, "chargeId"),
                new WalletId(EventPayload.uuid(data, "walletId")),
                amount,
                gross,
                EventPayload.text(data, "source"),
                EventPayload.optionalText(data, "reference"));
    }
}
