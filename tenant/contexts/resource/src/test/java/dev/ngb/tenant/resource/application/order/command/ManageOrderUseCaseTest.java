package dev.ngb.tenant.resource.application.order.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.billing.api.model.Subscription;
import dev.ngb.tenant.billing.api.model.SubscriptionDetail;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.resource.api.order.PricingModel;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.resource.domain.order.OrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ManageOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-7000-8000-0000000000c2");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final FakeCustomers customers = new FakeCustomers();
    private final InMemoryUnitOfWork unitOfWork = new InMemoryUnitOfWork(orders);
    private final FakeSubscriptions subscriptions = new FakeSubscriptions(unitOfWork);
    private final ManageOrderUseCase manage =
            new ManageOrderUseCase(orders, customers, subscriptions, unitOfWork, () -> NOW);

    @Test
    void cancelsTheOrderAndItsSubscriptionWithTheRemoteCallOutsideTheTransactions() {
        orders.save(subscribed(CUSTOMER));

        Order canceled = manage.cancel(ORDER_ID, USER, false);

        assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.subscriptionId()).isEqualTo("sub_1");
        assertThat(subscriptions.canceled).containsExactly("sub_1");
        assertThat(orders.findById(ORDER_ID).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(unitOfWork.transactions)
                .as("one transaction writes CANCELING, one records a failure if any, one closes the order")
                .isEqualTo(2);
        assertThat(subscriptions.cancelCallsWhileTransactionOpen).isZero();
    }

    @Test
    void anAdministratorCancelsAnotherCustomersOrder() {
        orders.save(subscribed(OTHER_CUSTOMER));

        assertThat(manage.cancel(ORDER_ID, USER, true).status()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void refusesAnOrderOfAnotherCustomer() {
        orders.save(subscribed(OTHER_CUSTOMER));

        assertThatThrownBy(() -> manage.cancel(ORDER_ID, USER, false)).isInstanceOf(NotFoundException.class);
        assertThat(subscriptions.canceled).isEmpty();
    }

    @Test
    void refusesAnUnknownOrder() {
        assertThatThrownBy(() -> manage.cancel(UUID.randomUUID(), USER, false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void refusesAnOrderThatIsAlreadyTerminal() {
        orders.save(subscribed(CUSTOMER).canceled(NOW));

        assertThatThrownBy(() -> manage.cancel(ORDER_ID, USER, false)).isInstanceOf(BadRequestException.class);
        assertThat(subscriptions.canceled).isEmpty();
    }

    @Test
    void aLocalSaveThatFailsLeavesTheOrderCancelingForTheReaper() {
        orders.save(subscribed(CUSTOMER));
        orders.failSavesFor(OrderStatus.CANCELED);

        assertThatThrownBy(() -> manage.cancel(ORDER_ID, USER, false)).isInstanceOf(IllegalStateException.class);

        assertThat(subscriptions.canceled).containsExactly("sub_1");
        assertThat(orders.findById(ORDER_ID).orElseThrow().status())
                .as("Billing was already told, so the local row must not look untouched")
                .isEqualTo(OrderStatus.CANCELING);
    }

    @Test
    void aFailedRemoteCancelLeavesTheOrderCancelingWithTheReason() {
        orders.save(subscribed(CUSTOMER));
        subscriptions.fail = true;

        assertThatThrownBy(() -> manage.cancel(ORDER_ID, USER, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billing is down");

        Order stuck = orders.findById(ORDER_ID).orElseThrow();
        assertThat(stuck.status()).isEqualTo(OrderStatus.CANCELING);
        assertThat(stuck.failureReason()).isEqualTo("billing is down");
    }

    private static Order subscribed(UUID customerId) {
        return Order.pending(
                        () -> ORDER_ID,
                        customerId,
                        "cus_1",
                        "wal_1",
                        UUID.fromString("00000000-0000-7000-8000-0000000000a1"),
                        "bplan_1",
                        PricingModel.SUBSCRIPTION,
                        "compute_hours",
                        "1",
                        "hour",
                        null,
                        "USD",
                        "ORD-" + ORDER_ID,
                        NOW)
                .queued(0, null, "sub_1", NOW);
    }

    private static final class InMemoryUnitOfWork implements UnitOfWork {

        private final InMemoryOrders orders;
        private int transactions;
        private boolean open;

        private InMemoryUnitOfWork(InMemoryOrders orders) {
            this.orders = orders;
        }

        boolean open() {
            return open;
        }

        @Override
        public <T> T transactional(Supplier<T> work) {
            transactions++;
            Map<UUID, Order> rows = new LinkedHashMap<>(orders.rows);
            open = true;
            try {
                return work.get();
            } catch (RuntimeException e) {
                orders.rows.clear();
                orders.rows.putAll(rows);
                throw e;
            } finally {
                open = false;
            }
        }
    }

    private static final class InMemoryOrders implements OrderRepository {
        private final Map<UUID, Order> rows = new LinkedHashMap<>();
        private OrderStatus failSavesFor;

        void failSavesFor(OrderStatus status) {
            failSavesFor = status;
        }

        @Override
        public void save(Order order) {
            if (order.status() == failSavesFor) {
                throw new IllegalStateException("the order row could not be written");
            }
            rows.put(order.id(), order);
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
        public List<Order> findCancelingBefore(Instant updatedBefore) {
            return rows.values().stream()
                    .filter(order -> order.status() == OrderStatus.CANCELING)
                    .filter(order -> order.updatedAt().isBefore(updatedBefore))
                    .toList();
        }

        @Override
        public List<Order> findByBillingCustomerId(String billingCustomerId) {
            return rows.values().stream()
                    .filter(order -> billingCustomerId.equals(order.billingCustomerId()))
                    .toList();
        }
    }

    private static final class FakeCustomers implements CustomerDirectory {
        @Override
        public CustomerSnapshot byUserId(UUID userId) {
            return new CustomerSnapshot(CUSTOMER, userId, "cus_1", true);
        }
    }

    private static final class FakeSubscriptions implements BillingSubscriptions {
        private final List<String> canceled = new ArrayList<>();
        private final InMemoryUnitOfWork unitOfWork;
        private boolean fail;
        private int cancelCallsWhileTransactionOpen;

        private FakeSubscriptions(InMemoryUnitOfWork unitOfWork) {
            this.unitOfWork = unitOfWork;
        }

        @Override
        public SubscriptionDetail subscribe(String idempotencyKey, String walletId, String planId) {
            return detail();
        }

        @Override
        public SubscriptionDetail subscription(String id) {
            return detail();
        }

        @Override
        public SubscriptionDetail cancel(String id) {
            if (unitOfWork != null && unitOfWork.open()) {
                cancelCallsWhileTransactionOpen++;
            }
            if (fail) {
                throw new IllegalStateException("billing is down");
            }
            canceled.add(id);
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
}
