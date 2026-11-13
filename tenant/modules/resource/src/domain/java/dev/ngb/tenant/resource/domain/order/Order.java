package dev.ngb.tenant.resource.domain.order;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.tenant.resource.api.order.PricingModel;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One ordered resource. It records the Billing plan, wallet and reservation that were used, so the
 * execution worker settles exactly the hold this order created.
 *
 * <p>The lifecycle is an explicit map: a transition is allowed only along a documented edge, and a
 * terminal order never moves again.
 */
public record Order(
        UUID id,
        UUID customerId,
        String billingCustomerId,
        String walletId,
        UUID catalogPlanId,
        String billingPlanId,
        PricingModel pricingModel,
        String metric,
        String usageQuantity,
        String unit,
        Long eventCount,
        long estimatedMinorUnits,
        String currency,
        String reservationId,
        String subscriptionId,
        OrderStatus status,
        String reference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = transitions();

    public static Order pending(
            IdGenerator ids,
            UUID customerId,
            String billingCustomerId,
            String walletId,
            UUID catalogPlanId,
            String billingPlanId,
            PricingModel pricingModel,
            String metric,
            String usageQuantity,
            String unit,
            Long eventCount,
            String currency,
            String reference,
            Instant now) {
        return new Order(
                ids.newId(),
                customerId,
                billingCustomerId,
                walletId,
                catalogPlanId,
                billingPlanId,
                pricingModel,
                metric,
                usageQuantity,
                unit,
                eventCount,
                0,
                currency,
                null,
                null,
                OrderStatus.PENDING,
                reference,
                null,
                now,
                now);
    }

    public static boolean allows(OrderStatus from, OrderStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public Order queued(long estimatedMinorUnits, String reservationId, String subscriptionId, Instant now) {
        requireFrom(OrderStatus.PENDING);
        return next(OrderStatus.QUEUED, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order requeued(Instant now) {
        requireFrom(OrderStatus.RUNNING);
        return next(OrderStatus.QUEUED, null, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order resumed(Instant now) {
        requireFrom(OrderStatus.SUSPENDED);
        return next(OrderStatus.QUEUED, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    private void requireFrom(OrderStatus expected) {
        if (status != expected) {
            throw new BadRequestException("order " + id + " cannot move from " + status + " to " + OrderStatus.QUEUED);
        }
    }

    public Order running(Instant now) {
        return next(OrderStatus.RUNNING, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order completed(Instant now) {
        return next(OrderStatus.COMPLETED, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order failed(String reason, Instant now) {
        return next(OrderStatus.FAILED, reason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order suspended(Instant now) {
        return next(OrderStatus.SUSPENDED, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public Order canceled(Instant now) {
        return next(OrderStatus.CANCELED, failureReason, estimatedMinorUnits, reservationId, subscriptionId, now);
    }

    public boolean terminal() {
        return TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }

    private Order next(
            OrderStatus next,
            String reason,
            long estimated,
            String reservation,
            String subscription,
            Instant now) {
        if (!allows(status, next)) {
            throw new BadRequestException("order " + id + " cannot move from " + status + " to " + next);
        }
        return new Order(
                id,
                customerId,
                billingCustomerId,
                walletId,
                catalogPlanId,
                billingPlanId,
                pricingModel,
                metric,
                usageQuantity,
                unit,
                eventCount,
                estimated,
                currency,
                reservation,
                subscription,
                next,
                reference,
                reason,
                createdAt,
                now);
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        Map<OrderStatus, Set<OrderStatus>> allowed = new EnumMap<>(OrderStatus.class);
        allowed.put(OrderStatus.PENDING, EnumSet.of(OrderStatus.QUEUED, OrderStatus.FAILED, OrderStatus.CANCELED));
        allowed.put(
                OrderStatus.QUEUED,
                EnumSet.of(OrderStatus.RUNNING, OrderStatus.SUSPENDED, OrderStatus.FAILED, OrderStatus.CANCELED));
        allowed.put(
                OrderStatus.RUNNING,
                EnumSet.of(
                        OrderStatus.QUEUED,
                        OrderStatus.COMPLETED,
                        OrderStatus.SUSPENDED,
                        OrderStatus.FAILED,
                        OrderStatus.CANCELED));
        allowed.put(
                OrderStatus.SUSPENDED,
                EnumSet.of(OrderStatus.QUEUED, OrderStatus.FAILED, OrderStatus.CANCELED));
        allowed.put(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class));
        allowed.put(OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class));
        allowed.put(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(allowed);
    }
}
