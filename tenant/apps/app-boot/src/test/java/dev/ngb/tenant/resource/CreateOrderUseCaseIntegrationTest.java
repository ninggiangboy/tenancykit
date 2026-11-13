package dev.ngb.tenant.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingPricing;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.catalog.api.lookup.CatalogLookup;
import dev.ngb.tenant.catalog.application.plan.port.CatalogPlanRepository;
import dev.ngb.tenant.catalog.domain.plan.CatalogPlan;
import dev.ngb.tenant.catalog.domain.plan.ResourceType;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.customer.application.customer.port.CustomerRepository;
import dev.ngb.tenant.customer.domain.customer.Customer;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.tenant.resource.api.execution.ExecutionQueue.Claimed;
import dev.ngb.tenant.resource.application.order.command.CreateOrderUseCase;
import dev.ngb.tenant.resource.application.order.port.OrderQueueRepository;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.application.order.query.OrderView;
import dev.ngb.tenant.resource.application.order.query.OrderReadRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.support.HttpIntegrationTest;
import dev.ngb.tenant.support.HttpWorld;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

class CreateOrderUseCaseIntegrationTest extends HttpIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired
    CatalogLookup catalog;

    @Autowired
    CustomerDirectory customerDirectory;

    @Autowired
    @Qualifier("billingPricing")
    BillingPricing pricing;

    @Autowired
    @Qualifier("billingSubscriptions")
    BillingSubscriptions subscriptions;

    @Autowired
    @Qualifier("billingWallets")
    BillingWallets wallets;

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderReadRepository read;

    @Autowired
    CreateOrderUseCase create;

    @Autowired
    UnitOfWork unitOfWork;

    @Autowired
    CustomerRepository customers;

    @Autowired
    CatalogPlanRepository plans;

    @Autowired
    Clock clock;

    @Autowired
    IdGenerator ids;

    @Test
    void anEnqueueThatFailsFailsThePendingOrderInsteadOfLeavingItQueued() {
        User user = register();
        Customer customer = customers.findByUserId(user.id()).orElseThrow();
        CatalogPlan plan = plan("atomic");

        CreateOrderUseCase failingQueue = new CreateOrderUseCase(
                catalog, customerDirectory, pricing, subscriptions, wallets, orders, failing(), unitOfWork,
                clock, ids, Duration.ofMinutes(15));

        assertThatThrownBy(() -> failingQueue.execute(
                        new CreateOrderUseCase.Command(user.id(), plan.id(), "120", null, "atomic-" + UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(read.forCustomer(customer.id())).singleElement().satisfies(order -> {
            assertThat(order.status()).isEqualTo("FAILED");
            assertThat(order.failureReason()).isEqualTo("queue is down");
            assertThat(order.reservationId()).isEqualTo("res_1");
        });
    }

    @Test
    void theSameKeyQueuesTheOrderOnce() {
        User user = register();
        Customer customer = customers.findByUserId(user.id()).orElseThrow();
        CatalogPlan plan = plan("replay");
        String key = "replay-" + UUID.randomUUID();

        OrderView first = view(create.execute(new CreateOrderUseCase.Command(user.id(), plan.id(), "120", null, key)));
        OrderView replay = view(create.execute(new CreateOrderUseCase.Command(user.id(), plan.id(), "120", null, key)));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("QUEUED");
        assertThat(read.forCustomer(customer.id())).hasSize(1);
    }

    private User register() {
        String email = "atomic-" + UUID.randomUUID() + "@example.com";
        return registerUser.execute(new RegisterUserUseCase.Command(email, "Ada", HttpWorld.PASSWORD, Role.VIEWER));
    }

    private CatalogPlan plan(String prefix) {
        CatalogPlan plan = new CatalogPlan(
                UUID.randomUUID(), prefix + "-" + UUID.randomUUID(), "Compute",
                ResourceType.COMPUTE, "description", "bplan_1", true, NOW);
        plans.save(plan);
        return plan;
    }

    private OrderView view(Order order) {
        return read.byId(order.id()).orElseThrow();
    }

    private static OrderQueueRepository failing() {
        return new OrderQueueRepository() {
            @Override
            public void enqueue(UUID orderId, Instant enqueuedAt) {
                throw new IllegalStateException("queue is down");
            }

            @Override
            public List<Claimed> claim(int batchSize) {
                return List.of();
            }

            @Override
            public void finish(UUID orderId) {}

            @Override
            public void requeue(UUID orderId) {}
        };
    }
}
