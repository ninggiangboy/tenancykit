package dev.ngb.crm.orders.domain.order;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.crm.orders.domain.returns.OrderReturn;
import dev.ngb.crm.orders.domain.returns.ReturnLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the order feature announces: an order that was recorded, and an order whose lines a return changed.
 *
 * <p>Both carry the order's complete current state — the lines with their cumulative returned quantities and amounts,
 * and the {@code orderVersion} that rose with the change — so a receiver can keep the order without reading crm back.
 * {@code order.refunded} also carries the return that caused it, so a Tenant that forwards it needs no other call.
 * The payloads are the contract in {@code contracts/events/crm}, and the two shapes differ only in that return block.
 */
public final class OrderEvents {

    /** An order and its lines were recorded. */
    public static final String ORDER_RECORDED = "order.recorded";

    /** A return was recorded against an order, and the order's state changed with it. */
    public static final String ORDER_REFUNDED = "order.refunded";

    private OrderEvents() {}

    /**
     * An order now exists with these lines. The customer is named by crm's id and by the Tenant's own reference, so a
     * receiver matches the order to its customer without a second read.
     */
    public record Recorded(
            UUID eventId,
            TenantId tenantId,
            OrderId orderId,
            String orderRef,
            UUID customerId,
            String externalRef,
            String currency,
            long total,
            Instant orderOccurredAt,
            String storeCode,
            String channel,
            List<Map<String, Object>> lines,
            long orderVersion,
            Instant recordedAt)
            implements DomainEvent {

        public Recorded {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(orderRef, "orderRef");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(externalRef, "externalRef");
            lines = List.copyOf(lines);
        }

        @Override
        public String type() {
            return ORDER_RECORDED;
        }

        @Override
        public String aggregate() {
            return "order/" + orderRef;
        }

        @Override
        public Instant occurredAt() {
            return recordedAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = state(customerId, externalRef, orderRef, orderVersion, currency, total,
                    orderOccurredAt, storeCode, channel, lines);
            return payload;
        }
    }

    /**
     * A return changed an order: it carries the order's state after the return and the return that caused it, so a
     * forwarder can replay the return elsewhere from this delivery alone.
     */
    public record Refunded(
            UUID eventId,
            TenantId tenantId,
            OrderId orderId,
            String orderRef,
            UUID customerId,
            String externalRef,
            String currency,
            long total,
            Instant orderOccurredAt,
            String storeCode,
            String channel,
            List<Map<String, Object>> lines,
            long orderVersion,
            Instant recordedAt,
            OrderReturn caused)
            implements DomainEvent {

        public Refunded {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(orderRef, "orderRef");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(externalRef, "externalRef");
            Objects.requireNonNull(caused, "caused");
            lines = List.copyOf(lines);
        }

        @Override
        public String type() {
            return ORDER_REFUNDED;
        }

        @Override
        public String aggregate() {
            return "order/" + orderRef;
        }

        @Override
        public Instant occurredAt() {
            return recordedAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = state(customerId, externalRef, orderRef, orderVersion, currency, total,
                    orderOccurredAt, storeCode, channel, lines);
            payload.put("return", returnBlock(caused.returnRef(), caused.occurredAt(), caused.lines()));
            return payload;
        }
    }

    /** The state both events carry: the order, its customer and every line with its cumulative returned figures. */
    private static Map<String, Object> state(
            UUID customerId,
            String externalRef,
            String orderRef,
            long orderVersion,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            List<Map<String, Object>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId.toString());
        payload.put("externalRef", externalRef);
        payload.put("orderRef", orderRef);
        payload.put("orderVersion", orderVersion);
        payload.put("currency", currency);
        payload.put("total", total);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("storeCode", storeCode);
        payload.put("channel", channel);
        payload.put("lines", lines);
        return payload;
    }

    /** One return as the {@code order.refunded} payload carries it: the reference, when, and what came back. */
    public static Map<String, Object> returnBlock(String returnRef, Instant occurredAt, List<ReturnLine> lines) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("returnRef", returnRef);
        block.put("occurredAt", occurredAt.toString());
        List<Map<String, Object>> returned = new ArrayList<>(lines.size());
        for (ReturnLine line : lines) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lineNo", line.lineNo());
            entry.put("quantity", line.quantity());
            entry.put("amount", line.amount());
            returned.add(entry);
        }
        block.put("lines", returned);
        return block;
    }
}
