package dev.ngb.billing.payment.domain.refund;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.billing.payment.domain.payment.Payment;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** What refunds announce. Payloads are the integration contract: ids, amounts in minor units and codes, no personal data. */
public final class RefundEvents {

    private RefundEvents() {}

    /** Money went back to the customer's card and the wallet was debited for it. */
    public record RefundSucceeded(UUID eventId, Refund refund, Payment payment, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.REFUND_SUCCEEDED;

        @Override
        public TenantId tenantId() {
            return refund.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.payment(refund.paymentId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("refundId", refund.id().toString(), "paymentId", refund.paymentId().toString(), "walletId",
                    payment.walletId().toString(), "amount", MoneyEventPayload.money(refund.amount()), "ledgerEntryId", refund.ledgerEntryId().toString());
        }
    }
}
