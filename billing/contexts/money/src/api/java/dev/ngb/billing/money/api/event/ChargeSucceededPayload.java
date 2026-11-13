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
 */
public record ChargeSucceededPayload(UUID chargeId, WalletId walletId, Money amount, String source, String reference) {

    /**
     * @throws MalformedEventException when the payload is not a {@code charge.succeeded} payload
     */
    public static ChargeSucceededPayload parse(Map<String, Object> data) {
        return new ChargeSucceededPayload(
                EventPayload.uuid(data, "chargeId"),
                new WalletId(EventPayload.uuid(data, "walletId")),
                EventPayload.money(data, "amount"),
                EventPayload.text(data, "source"),
                EventPayload.optionalText(data, "reference"));
    }
}
