package dev.ngb.billing.payment.domain.payment;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.billing.payment.api.payment.PaymentEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the payment context announces, written to the outbox in the transaction of the change it reports.
 * Payloads are the integration contract: ids, amounts and codes, never a Stripe secret or card data.
 */
public final class PaymentEvents {

    private PaymentEvents() {}

    /** A top-up was paid and the wallet credited, or an invoice payment was paid (the wallet is then credited by invoicing). */
    public record PaymentSucceeded(UUID eventId, Payment payment, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = PaymentEventTypes.PAYMENT_SUCCEEDED;

        @Override
        public TenantId tenantId() {
            return payment.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.payment(payment.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("paymentId", payment.id().toString(), "walletId", payment.walletId().toString(), "customerId",
                    payment.customerId().toString(), "amount", MoneyEventPayload.money(payment.amount()), "ledgerEntryId",
                    payment.ledgerEntryId() == null ? null : payment.ledgerEntryId().toString(), "invoiceId",
                    payment.invoiceId() == null ? null : payment.invoiceId().toString());
        }
    }

    /** A top-up ended without money: Stripe could not collect it. */
    public record PaymentFailed(UUID eventId, Payment payment, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = PaymentEventTypes.PAYMENT_FAILED;

        @Override
        public TenantId tenantId() {
            return payment.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.payment(payment.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("paymentId", payment.id().toString(), "walletId", payment.walletId().toString(), "customerId",
                    payment.customerId().toString(), "amount", MoneyEventPayload.money(payment.amount()), "reason", payment.failureReason(), "invoiceId",
                    payment.invoiceId() == null ? null : payment.invoiceId().toString());
        }
    }
}
