package dev.ngb.billing.payment.api.payment;

import dev.ngb.billing.sharedkernel.event.EventPayload;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Map;
import java.util.UUID;

/**
 * What a verified payment result carries, as invoicing reads it: {@code payment.succeeded} holds the payment, the
 * invoice it settles and the amount, and {@code payment.failed} the same shape plus the reason. {@code invoiceId}
 * is null for a top-up, which settles no invoice. The payload keys are the integration contract, so
 * {@link #parse(Map)} is the only place they are named.
 */
public record PaymentSucceededPayload(UUID paymentId, UUID invoiceId, Money amount, String reason) {

    /**
     * @throws MalformedEventException when the payload is not a verified payment result
     */
    public static PaymentSucceededPayload parse(Map<String, Object> data) {
        return new PaymentSucceededPayload(
                EventPayload.uuid(data, "paymentId"),
                EventPayload.optionalUuid(data, "invoiceId"),
                EventPayload.money(data, "amount"),
                EventPayload.optionalText(data, "reason"));
    }
}
