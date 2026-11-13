package dev.ngb.tenant.resource.application.order.command;

import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingPricing;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.billing.api.model.Estimate;
import dev.ngb.tenant.billing.api.model.PricingPlan;
import dev.ngb.tenant.billing.api.model.ReserveResult;
import dev.ngb.tenant.billing.api.model.SubscriptionDetail;
import dev.ngb.tenant.billing.api.model.UsageInput;
import dev.ngb.tenant.billing.api.model.Wallet;
import dev.ngb.tenant.catalog.api.lookup.CatalogLookup;
import dev.ngb.tenant.catalog.api.lookup.PricingModel;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.resource.application.order.port.OrderQueueRepository;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.resource.domain.order.OrderStatus;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scenarios 2, 3 and 4: check the Billing plan, estimate the cost and check the wallet, then persist a
 * PENDING order carrying the deterministic reference of the caller's idempotency key, reserve or
 * subscribe with that same reference, and finally commit the QUEUED order with its queue row.
 *
 * <p>The remote call stays outside the final transaction. A failure after the order exists fails the
 * pending row instead of leaving it half-applied, and a crash that leaves the row pending is expired
 * by {@link #expirePending()} once the reservation can no longer be live.
 */
public class CreateOrderUseCase {

    private static final String EXPIRED_REASON = "the order was not confirmed before its reservation could expire";

    public record Command(UUID userId, UUID planId, String quantity, Long events, String idempotencyKey) {}

    private final CatalogLookup catalog;
    private final CustomerDirectory customers;
    private final BillingPricing pricing;
    private final BillingSubscriptions subscriptions;
    private final BillingWallets wallets;
    private final OrderRepository orders;
    private final OrderQueueRepository queue;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final IdGenerator ids;
    private final Duration reservationTtl;

    public CreateOrderUseCase(
            CatalogLookup catalog,
            CustomerDirectory customers,
            BillingPricing pricing,
            BillingSubscriptions subscriptions,
            BillingWallets wallets,
            OrderRepository orders,
            OrderQueueRepository queue,
            UnitOfWork unitOfWork,
            Clock clock,
            IdGenerator ids,
            Duration reservationTtl) {
        this.catalog = catalog;
        this.customers = customers;
        this.pricing = pricing;
        this.subscriptions = subscriptions;
        this.wallets = wallets;
        this.orders = orders;
        this.queue = queue;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.ids = ids;
        this.reservationTtl = reservationTtl;
    }

    public Order execute(Command command) {
        String idempotencyKey = idempotencyKey(command.idempotencyKey());
        CatalogLookup.PlanSnapshot plan = catalog.get(command.planId());
        if (!plan.billable()) {
            throw new BadRequestException("this plan is not bound to a Billing plan yet");
        }
        CustomerDirectory.CustomerSnapshot customer = customers.byUserId(command.userId());
        if (!customer.mapped()) {
            throw new BadRequestException("this account has no Billing customer mapping yet");
        }
        PricingPlan billingPlan = pricing.plan(plan.billingPlanId());
        if ("RETIRED".equals(billingPlan.status())) {
            throw new BadRequestException("this Billing plan is retired");
        }
        String currency = billingPlan.currency();
        Wallet wallet = wallets.wallets(customer.billingCustomerId()).stream()
                .filter(candidate -> currency.equals(candidate.currency()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("no " + currency + " wallet for this customer"));

        String quantity = command.quantity() == null || command.quantity().isBlank() ? "1" : command.quantity();
        Long events = eventCount(plan, quantity, command.events());
        String reference = reference(customer.id(), idempotencyKey);
        Order replay = orders.findByReference(reference).orElse(null);
        if (replay != null && replay.status() != OrderStatus.PENDING) {
            return replay;
        }
        Order pending = replay != null ? replay : persist(plan, customer, wallet, quantity, events, currency, reference);
        return confirm(pending, plan, wallet, quantity, events, currency);
    }

    public int expirePending() {
        Instant now = clock.now();
        List<Order> stale = orders.findPendingBefore(now.minus(reservationTtl));
        int expired = 0;
        for (Order order : stale) {
            unitOfWork.transactional(() -> {
                orders.save(order.failed(EXPIRED_REASON, now));
                return null;
            });
            expired++;
        }
        return expired;
    }

    private Order persist(
            CatalogLookup.PlanSnapshot plan,
            CustomerDirectory.CustomerSnapshot customer,
            Wallet wallet,
            String quantity,
            Long events,
            String currency,
            String reference) {
        Order pending = Order.pending(
                ids,
                customer.id(),
                customer.billingCustomerId(),
                wallet.id(),
                plan.id(),
                plan.billingPlanId(),
                dev.ngb.tenant.resource.api.order.PricingModel.valueOf(plan.pricingModel().name()),
                plan.metric(),
                quantity,
                plan.unit(),
                events,
                currency,
                reference,
                clock.now());
        unitOfWork.transactional(() -> {
            orders.save(pending);
            return null;
        });
        return pending;
    }

    private Order confirm(
            Order pending,
            CatalogLookup.PlanSnapshot plan,
            Wallet wallet,
            String quantity,
            Long events,
            String currency) {
        String reference = pending.reference();
        String reservationId = null;
        String subscriptionId = null;
        long amount = 0;
        try {
            if (subscriptionPlan(plan)) {
                SubscriptionDetail started = subscriptions.subscribe(
                        "subscription:" + reference, wallet.id(), plan.billingPlanId());
                subscriptionId = started.subscription().id();
            } else {
                Estimate estimate = pricing.estimate(new BillingPricing.EstimateRequest(
                        plan.billingPlanId(), null, clock.now(), usage(plan, quantity, events)));
                amount = estimate.amount() == null ? 0 : estimate.amount().amount();
                if (amount > 0) {
                    ReserveResult reserved = pricing.reserve(
                            "reserve:" + reference,
                            new BillingPricing.ReserveRequest(
                                    wallet.id(), amount, currency, reservationTtl.toSeconds(), reference));
                    reservationId = reserved.reservation().id();
                }
            }
        } catch (RuntimeException e) {
            fail(pending, e);
            throw e;
        }
        return commit(pending.queued(amount, reservationId, subscriptionId, clock.now()));
    }

    private Order commit(Order order) {
        try {
            unitOfWork.transactional(() -> {
                orders.save(order);
                queue.enqueue(order.id(), order.createdAt());
                return null;
            });
        } catch (RuntimeException e) {
            fail(order, e);
            throw e;
        }
        return order;
    }

    private void fail(Order order, RuntimeException cause) {
        try {
            unitOfWork.transactional(() -> {
                orders.save(order.failed(reason(cause), clock.now()));
                return null;
            });
        } catch (RuntimeException compensation) {
            cause.addSuppressed(compensation);
        }
    }

    private static String idempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("an Idempotency-Key header is required");
        }
        return idempotencyKey.trim();
    }

    private static String reference(UUID customerId, String idempotencyKey) {
        String material = IdempotencyKeys.order(customerId, idempotencyKey);
        return "ORD-" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private static boolean subscriptionPlan(CatalogLookup.PlanSnapshot plan) {
        return plan.pricingModel() == PricingModel.SUBSCRIPTION || plan.pricingModel() == PricingModel.HYBRID;
    }

    private static List<UsageInput> usage(CatalogLookup.PlanSnapshot plan, String quantity, Long events) {
        if (plan.pricingModel() == PricingModel.SUBSCRIPTION) {
            return List.of();
        }
        return List.of(new UsageInput(plan.metric(), quantity, events));
    }

    private static Long eventCount(CatalogLookup.PlanSnapshot plan, String quantity, Long events) {
        if (plan.pricingModel() != PricingModel.EVENT_BASED) {
            return events;
        }
        if (events != null) {
            return events;
        }
        try {
            return Long.parseLong(quantity);
        } catch (NumberFormatException e) {
            throw new BadRequestException("an event-based order needs a whole quantity");
        }
    }
}
