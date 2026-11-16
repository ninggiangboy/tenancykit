package dev.ngb.crm.orders.domain.order;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.orders.domain.returns.OrderReturn;
import dev.ngb.crm.orders.domain.returns.ReturnId;
import dev.ngb.crm.orders.domain.returns.ReturnLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One order as the Tenant's own system recorded it: the items sold, the customer they were sold to and how much of
 * them has come back.
 *
 * <p>The order itself never changes after it is recorded — a correction is a return. A return moves the cumulative
 * returned figures of the lines, derives the status and bumps the version, which is the order's ETag and the
 * {@code orderVersion} of every order webhook. The version therefore says how many returns an order has seen, and a
 * receiver that keeps the order's state drops a delivery that is not newer.
 *
 * <p>The customer is crm's own id. The Tenant's reference for it is the customers context's fact and is supplied
 * where the order is announced or read, so an erasure of the person cannot leave a stale reference behind here.
 */
public final class Order {

    private final OrderId id;
    private final TenantId tenantId;
    private final String orderRef;
    private final UUID customerId;
    private final String currency;
    private final long total;
    private final Instant occurredAt;
    private final String storeCode;
    private final String channel;
    private final String fingerprint;
    private final Instant createdAt;
    private List<OrderLine> lines;
    private List<OrderReturn> returns;
    private OrderStatus status;
    private long version;

    private Order(
            OrderId id,
            TenantId tenantId,
            String orderRef,
            UUID customerId,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            String fingerprint,
            Instant createdAt,
            List<OrderLine> lines,
            List<OrderReturn> returns,
            OrderStatus status,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.orderRef = Objects.requireNonNull(orderRef, "orderRef");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.total = total;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.storeCode = storeCode;
        this.channel = channel;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lines = List.copyOf(lines);
        this.returns = List.copyOf(returns);
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
    }

    /** An order that did not exist before: version 1, nothing returned. */
    public static Order record(
            OrderId id,
            TenantId tenantId,
            String orderRef,
            UUID customerId,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            String fingerprint,
            List<OrderLine> lines,
            Instant now) {
        return new Order(
                id,
                tenantId,
                orderRef,
                customerId,
                currency,
                total,
                occurredAt,
                storeCode,
                channel,
                fingerprint,
                now,
                lines,
                List.of(),
                OrderStatus.RECORDED,
                1);
    }

    /** The order a repository reads back from storage. */
    public static Order restore(
            OrderId id,
            TenantId tenantId,
            String orderRef,
            UUID customerId,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            String fingerprint,
            Instant createdAt,
            List<OrderLine> lines,
            List<OrderReturn> returns,
            OrderStatus status,
            long version) {
        return new Order(
                id,
                tenantId,
                orderRef,
                customerId,
                currency,
                total,
                occurredAt,
                storeCode,
                channel,
                fingerprint,
                createdAt,
                lines,
                returns,
                status,
                version);
    }

    /**
     * Applies a return that the caller has already checked against the sold quantities.
     *
     * <p>The figures move by exactly what the return took back, the status follows the lines, the version rises by
     * one and the return joins the order's history. A return that names a line number the order does not have is a
     * caller error, not a state this aggregate may reach.
     *
     * @return the return as recorded, numbered by the version it produced
     */
    public OrderReturn applyReturn(
            ReturnId returnId, String returnRef, Instant returnedAt, List<ReturnLine> returned, Instant now) {
        Map<Integer, ReturnLine> byLine = new LinkedHashMap<>();
        for (ReturnLine line : returned) {
            byLine.put(line.lineNo(), line);
        }
        List<OrderLine> next = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            ReturnLine taken = byLine.get(line.lineNo());
            next.add(taken == null ? line : line.returned(taken.quantity(), taken.amount()));
        }
        long producedVersion = version + 1;
        OrderReturn recorded = new OrderReturn(returnId, returnRef, returnedAt, producedVersion, returned);
        this.lines = List.copyOf(next);
        this.returns = append(returns, recorded);
        this.status = statusOf(next);
        this.version = producedVersion;
        return recorded;
    }

    /** The announcement of this order's recording, ready for the outbox. */
    public OrderEvents.Recorded recorded(UUID eventId, String externalRef) {
        return new OrderEvents.Recorded(eventId, tenantId, id, orderRef, customerId, externalRef, currency, total,
                occurredAt, storeCode, channel, payloadLines(), version, createdAt);
    }

    /** The announcement of a return applied to this order, carrying the order's new state and the return itself. */
    public OrderEvents.Refunded refunded(UUID eventId, String externalRef, OrderReturn caused, Instant announcedAt) {
        return new OrderEvents.Refunded(eventId, tenantId, id, orderRef, customerId, externalRef, currency, total,
                occurredAt, storeCode, channel, payloadLines(), version, announcedAt, caused);
    }

    /** The state of the lines as a webhook carries it: quantity and amount as sold, plus what came back. */
    public List<Map<String, Object>> payloadLines() {
        List<Map<String, Object>> payload = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lineNo", line.lineNo());
            entry.put("sku", line.sku());
            entry.put("category", line.category());
            entry.put("quantity", line.quantity());
            entry.put("amount", line.amount());
            entry.put("earnEligible", line.earnEligible());
            entry.put("returnedQuantity", line.returnedQuantity());
            entry.put("returnedAmount", line.returnedAmount());
            payload.add(entry);
        }
        return payload;
    }

    private static List<OrderReturn> append(List<OrderReturn> existing, OrderReturn recorded) {
        List<OrderReturn> next = new ArrayList<>(existing.size() + 1);
        next.addAll(existing);
        next.add(recorded);
        return List.copyOf(next);
    }

    private static OrderStatus statusOf(List<OrderLine> lines) {
        boolean any = false;
        boolean all = true;
        for (OrderLine line : lines) {
            any = any || line.partiallyReturned();
            all = all && line.fullyReturned();
        }
        if (!any) {
            return OrderStatus.RECORDED;
        }
        return all ? OrderStatus.RETURNED : OrderStatus.PARTIALLY_RETURNED;
    }

    /** The line the caller means, or empty when the order has no line with that number. */
    public java.util.Optional<OrderLine> line(int lineNo) {
        for (OrderLine line : lines) {
            if (line.lineNo() == lineNo) {
                return java.util.Optional.of(line);
            }
        }
        return java.util.Optional.empty();
    }

    public OrderId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String orderRef() {
        return orderRef;
    }

    public UUID customerId() {
        return customerId;
    }

    public String currency() {
        return currency;
    }

    /** The order total in minor units. Informational: it is never an earn base, because the lines are. */
    public long total() {
        return total;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String storeCode() {
        return storeCode;
    }

    public String channel() {
        return channel;
    }

    /** The digest of the request that recorded this order, which tells a replay from a changed body. */
    public String fingerprint() {
        return fingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    /** The returns recorded against this order, oldest first. */
    public List<OrderReturn> returns() {
        return returns;
    }

    public OrderStatus status() {
        return status;
    }

    /** 1 when the order was recorded, one more for every return; the ETag and the {@code orderVersion}. */
    public long version() {
        return version;
    }

    /** @return the quantity sold on a line, which a return is checked against */
    public BigDecimal soldOn(int lineNo) {
        return line(lineNo).orElseThrow().quantity();
    }
}
