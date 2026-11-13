package dev.ngb.tenant.resource.application.order.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PaymentRequiredException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingPricing;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.billing.api.model.Amount;
import dev.ngb.tenant.billing.api.model.Estimate;
import dev.ngb.tenant.billing.api.model.PricingPlan;
import dev.ngb.tenant.billing.api.model.Reservation;
import dev.ngb.tenant.billing.api.model.ReserveResult;
import dev.ngb.tenant.billing.api.model.Subscription;
import dev.ngb.tenant.billing.api.model.SubscriptionDetail;
import dev.ngb.tenant.billing.api.model.UsageInput;
import dev.ngb.tenant.billing.api.model.Wallet;
import dev.ngb.tenant.catalog.api.lookup.CatalogLookup;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.resource.api.execution.ExecutionQueue.Claimed;
import dev.ngb.tenant.resource.application.order.port.OrderQueueRepository;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.resource.domain.order.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CreateOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final String KEY = "order-key-1";
    private static final String REFERENCE = "ORD-" + UUID.nameUUIDFromBytes(
            ("order:" + CUSTOMER + ":" + KEY).getBytes(StandardCharsets.UTF_8));

    private final FakeCatalog catalog = new FakeCatalog();
    private final FakeCustomers customers = new FakeCustomers();
    private final FakePricing pricing = new FakePricing();
    private final FakeSubscriptions subscriptions = new FakeSubscriptions();
    private final FakeWallets wallets = new FakeWallets();
    private final InMemoryOrders orders = new InMemoryOrders();
    private final InMemoryQueue queue = new InMemoryQueue();
    private final UnitOfWork unitOfWork = new InMemoryUnitOfWork(orders);
    private final CreateOrderUseCase create = new CreateOrderUseCase(
            catalog, customers, pricing, subscriptions, wallets, orders, queue, unitOfWork,
            () -> NOW, () -> UUID.randomUUID(), Duration.ofMinutes(15));

    @Test
    void persistsAPendingOrderConfirmsItWithBillingAndThenQueuesIt() {
        Order order = create.execute(command());

        assertThat(order.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(order.reference()).isEqualTo(REFERENCE);
        assertThat(order.reservationId()).isEqualTo("res_1");
        assertThat(order.estimatedMinorUnits()).isEqualTo(1000);
        assertThat(order.metric()).isEqualTo("compute.vcpu_minute");
        assertThat(pricing.estimated).isTrue();
        assertThat(pricing.reserved).isTrue();
        assertThat(orders.writes).extracting(Order::status).containsExactly(OrderStatus.PENDING, OrderStatus.QUEUED);
        assertThat(orders.writes.getFirst().reservationId()).isNull();
        assertThat(orders.writes.getLast()).isEqualTo(order);
        assertThat(queue.enqueued).containsExactly(order.id());
    }

    @Test
    void aRefusedReservationFailsThePendingOrderAndQueuesNothing() {
        pricing.refuse = true;

        assertThatThrownBy(() -> create.execute(command()))
                .isInstanceOf(PaymentRequiredException.class);

        assertThat(orders.writes).extracting(Order::status).containsExactly(OrderStatus.PENDING, OrderStatus.FAILED);
        assertThat(orders.writes.getLast().failureReason()).isEqualTo("The wallet cannot cover this amount.");
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void anEnqueueThatFailsLeavesNoQueuedOrder() {
        queue.fail = true;

        assertThatThrownBy(() -> create.execute(command())).isInstanceOf(IllegalStateException.class);

        assertThat(orders.writes).extracting(Order::status).containsExactly(OrderStatus.PENDING, OrderStatus.FAILED);
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void refusesAWriteWithoutAnIdempotencyKey() {
        assertThatThrownBy(() -> create.execute(new CreateOrderUseCase.Command(USER, PLAN, "120", null, " ")))
                .isInstanceOf(BadRequestException.class);

        assertThat(orders.writes).isEmpty();
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void theSameKeyReplaysTheOrderInsteadOfReservingTwice() {
        Order first = create.execute(command());
        pricing.reserved = false;

        Order second = create.execute(command());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(pricing.reserved).isFalse();
        assertThat(orders.writes).hasSize(2);
    }

    @Test
    void itResumesAPendingOrderACrashLeftBehind() {
        Order crashLeft = Order.pending(
                () -> UUID.fromString("00000000-0000-7000-8000-000000000099"),
                CUSTOMER, "cus_1", "wal_1", PLAN, "bplan_1", "TIME_BASED", "compute.vcpu_minute", "120",
                "vCPU-minute", null, "USD", REFERENCE, NOW.minusSeconds(30));
        orders.save(crashLeft);

        Order order = create.execute(command());

        assertThat(order.id()).isEqualTo(crashLeft.id());
        assertThat(order.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(order.reservationId()).isEqualTo("res_1");
        assertThat(queue.enqueued).containsExactly(crashLeft.id());
    }

    @Test
    void expiresOnlyAPendingOrderItsReservationCanNoLongerCover() {
        Order stale = Order.pending(
                () -> UUID.fromString("00000000-0000-7000-8000-000000000098"),
                CUSTOMER, "cus_1", "wal_1", PLAN, "bplan_1", "TIME_BASED", "compute.vcpu_minute", "120",
                "vCPU-minute", null, "USD", "ORD-stale", NOW.minus(Duration.ofHours(1)));
        Order fresh = Order.pending(
                () -> UUID.fromString("00000000-0000-7000-8000-000000000097"),
                CUSTOMER, "cus_1", "wal_1", PLAN, "bplan_1", "TIME_BASED", "compute.vcpu_minute", "120",
                "vCPU-minute", null, "USD", "ORD-fresh", NOW);
        orders.save(stale);
        orders.save(fresh);

        assertThat(create.expirePending()).isEqualTo(1);

        Order expired = orders.findById(stale.id()).orElseThrow();
        assertThat(expired.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(expired.failureReason()).isEqualTo("the order was not confirmed before its reservation could expire");
        assertThat(orders.findById(fresh.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void refusesAnUnmappedCustomerBeforeEstimating() {
        customers.mapped = false;

        assertThatThrownBy(() -> create.execute(command())).isInstanceOf(BadRequestException.class);
        assertThat(pricing.estimated).isFalse();
        assertThat(orders.writes).isEmpty();
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void refusesAPlanWithoutABillingBinding() {
        catalog.billable = false;

        assertThatThrownBy(() -> create.execute(command())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesWhenNoWalletHoldsThePlanCurrency() {
        wallets.currency = "EUR";

        assertThatThrownBy(() -> create.execute(command())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void refusesARetiredBillingPlan() {
        pricing.status = "RETIRED";

        assertThatThrownBy(() -> create.execute(command())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEventBasedOrderCarriesWholeEventsToTheEstimate() {
        catalog.model = "EVENT_BASED";

        create.execute(new CreateOrderUseCase.Command(USER, PLAN, "25", null, KEY));

        assertThat(pricing.lastUsage).singleElement().satisfies(usage -> {
            assertThat(usage.metric()).isEqualTo("compute.vcpu_minute");
            assertThat(usage.events()).isEqualTo(25L);
        });
    }

    @Test
    void aSubscriptionPlanStartsASubscriptionAndSkipsTheReservation() {
        catalog.model = "SUBSCRIPTION";

        Order order = create.execute(command());

        assertThat(order.subscriptionId()).isEqualTo("sub_1");
        assertThat(order.reservationId()).isNull();
        assertThat(order.estimatedMinorUnits()).isZero();
        assertThat(subscriptions.subscribed).isTrue();
        assertThat(pricing.estimated).isFalse();
        assertThat(pricing.reserved).isFalse();
    }

    @Test
    void aHybridPlanAlsoStartsASubscription() {
        catalog.model = "HYBRID";

        Order order = create.execute(command());

        assertThat(order.subscriptionId()).isEqualTo("sub_1");
        assertThat(subscriptions.subscribed).isTrue();
    }

    private static CreateOrderUseCase.Command command() {
        return new CreateOrderUseCase.Command(USER, PLAN, "120", null, KEY);
    }

    private static final class FakeCatalog implements CatalogLookup {
        private boolean billable = true;
        private String model = "TIME_BASED";

        @Override
        public PlanSnapshot get(UUID id) {
            return new PlanSnapshot(
                    id, "compute-standard", "Compute", "bplan_1", "COMPUTE", model, "compute.vcpu_minute", "vCPU-minute", billable);
        }
    }

    private static final class FakeCustomers implements CustomerDirectory {
        private boolean mapped = true;

        @Override
        public CustomerSnapshot byUserId(UUID userId) {
            return new CustomerSnapshot(CUSTOMER, userId, mapped ? "cus_1" : null, mapped);
        }
    }

    private static final class FakePricing implements BillingPricing {
        private boolean estimated;
        private boolean reserved;
        private boolean refuse;
        private String status = "ACTIVE";
        private List<UsageInput> lastUsage = List.of();

        @Override
        public List<PricingPlan> plans(String status) {
            return List.of();
        }

        @Override
        public PricingPlan plan(String id) {
            return new PricingPlan(id, "compute-standard", "Compute", "TIME_BASED", "USD", status, NOW, null, List.of());
        }

        @Override
        public Estimate estimate(EstimateRequest request) {
            estimated = true;
            lastUsage = request.usage();
            return new Estimate("bplan_1", "compute-standard", 1, new Amount(1000, "USD"), "10.00", "0", List.of());
        }

        @Override
        public ReserveResult reserve(String idempotencyKey, ReserveRequest request) {
            if (refuse) {
                throw new PaymentRequiredException("The wallet cannot cover this amount.");
            }
            reserved = true;
            return new ReserveResult(
                    new Estimate("bplan_1", "compute-standard", 1, new Amount(request.amount(), request.currency()), "10.00", "0", List.of()),
                    new Reservation(
                            "res_1", request.walletId(), "HELD", new Amount(request.amount(), request.currency()), null,
                            NOW.plusSeconds(900), request.reference(), NOW, null, false));
        }
    }

    private static final class FakeSubscriptions implements BillingSubscriptions {
        private boolean subscribed;

        @Override
        public SubscriptionDetail subscribe(String idempotencyKey, String walletId, String planId) {
            subscribed = true;
            return detail();
        }

        @Override
        public SubscriptionDetail subscription(String id) {
            return detail();
        }

        @Override
        public SubscriptionDetail cancel(String id) {
            return detail();
        }

        @Override
        public List<Subscription> subscriptions(String customerId, String walletId, String status, int limit) {
            return List.of(detail().subscription());
        }

        private static SubscriptionDetail detail() {
            return new SubscriptionDetail(
                    new Subscription("sub_1", "cus_1", "wal_1", "bplan_1", "managed-service-standard", "MONTHLY",
                            "ACTIVE", NOW, 1, NOW, NOW.plusSeconds(2_592_000), false, null, NOW.plusSeconds(2_592_000), 0, NOW),
                    List.of());
        }
    }

    private static final class FakeWallets implements BillingWallets {
        private String currency = "USD";

        @Override
        public List<Wallet> wallets(String customerId) {
            return List.of(new Wallet(
                    "wal_1", customerId, currency, "PREPAID", new Amount(5000, currency), new Amount(0, currency),
                    new Amount(5000, currency), new Amount(0, currency), new Amount(0, currency), 0L, NOW));
        }

        @Override
        public Wallet wallet(String id) {
            return wallets("cus_1").get(0);
        }

        @Override
        public Wallet open(String idempotencyKey, OpenWallet request) {
            return wallets(request.customerId()).get(0);
        }
    }

    private static final class InMemoryUnitOfWork implements UnitOfWork {

        private final InMemoryOrders orders;

        private InMemoryUnitOfWork(InMemoryOrders orders) {
            this.orders = orders;
        }

        @Override
        public <T> T transactional(Supplier<T> work) {
            List<Order> writes = List.copyOf(orders.writes);
            Map<UUID, Order> rows = new LinkedHashMap<>(orders.rows);
            try {
                return work.get();
            } catch (RuntimeException e) {
                orders.writes.clear();
                orders.writes.addAll(writes);
                orders.rows.clear();
                orders.rows.putAll(rows);
                throw e;
            }
        }
    }

    private static final class InMemoryOrders implements OrderRepository {
        private final Map<UUID, Order> rows = new LinkedHashMap<>();
        private final List<Order> writes = new ArrayList<>();

        @Override
        public void save(Order order) {
            rows.put(order.id(), order);
            writes.add(order);
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Order> findByReference(String reference) {
            return rows.values().stream().filter(order -> order.reference().equals(reference)).findFirst();
        }

        @Override
        public List<Order> findPendingBefore(Instant createdBefore) {
            return rows.values().stream()
                    .filter(order -> order.status() == OrderStatus.PENDING)
                    .filter(order -> order.createdAt().isBefore(createdBefore))
                    .toList();
        }

        @Override
        public List<Order> findByBillingCustomerId(String billingCustomerId) {
            return rows.values().stream()
                    .filter(order -> billingCustomerId.equals(order.billingCustomerId()))
                    .toList();
        }
    }

    private static final class InMemoryQueue implements OrderQueueRepository {
        private final List<UUID> enqueued = new ArrayList<>();
        private boolean fail;

        @Override
        public void enqueue(UUID orderId, Instant enqueuedAt) {
            if (fail) {
                throw new IllegalStateException("queue is down");
            }
            enqueued.add(orderId);
        }

        @Override
        public List<Claimed> claim(int batchSize) {
            return List.of();
        }

        @Override
        public void finish(UUID orderId) {}

        @Override
        public void requeue(UUID orderId) {}
    }
}
