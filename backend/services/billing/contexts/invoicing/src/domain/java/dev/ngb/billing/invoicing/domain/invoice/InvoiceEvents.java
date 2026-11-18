package dev.ngb.billing.invoicing.domain.invoice;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the invoicing context announces, written to the outbox in the transaction of the change it reports.
 * Payloads are the integration contract: ids, numbers, amounts and codes.
 */
public final class InvoiceEvents {

    private InvoiceEvents() {}

    private static Map<String, Object> invoiceFields(Invoice invoice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("invoiceId", invoice.id().toString());
        map.put("number", invoice.number());
        map.put("customerId", invoice.customerId().toString());
        map.put("walletId", invoice.walletId().toString());
        map.put("total", MoneyEventPayload.money(invoice.total()));
        return map;
    }

    /** An invoice was issued. */
    public record Created(UUID eventId, Invoice invoice, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.INVOICE_CREATED;

        @Override
        public TenantId tenantId() {
            return invoice.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.invoice(invoice.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> map = invoiceFields(invoice);
            map.put("origin", invoice.origin().name());
            map.put("status", invoice.status().name());
            map.put("itemCount", invoice.items().size());
            if (invoice.periodStart() != null) {
                map.put("periodStart", invoice.periodStart().toString());
                map.put("periodEnd", invoice.periodEnd().toString());
            }
            return map;
        }
    }

    /** An invoice was paid, by a verified payment or because the wallet had already paid it. */
    public record Paid(UUID eventId, Invoice invoice, UUID paymentId, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.INVOICE_PAID;

        @Override
        public TenantId tenantId() {
            return invoice.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.invoice(invoice.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> map = invoiceFields(invoice);
            map.put("settlement", invoice.settlement().name());
            if (paymentId != null) {
                map.put("paymentId", paymentId.toString());
            }
            map.put("paidAt", invoice.paidAt().toString());
            return map;
        }
    }
}
