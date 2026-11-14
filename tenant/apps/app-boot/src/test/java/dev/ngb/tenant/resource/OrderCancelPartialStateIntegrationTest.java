package dev.ngb.tenant.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.tenant.catalog.application.plan.port.CatalogPlanRepository;
import dev.ngb.tenant.catalog.domain.plan.CatalogPlan;
import dev.ngb.tenant.catalog.domain.plan.ResourceType;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.tenant.resource.api.order.OrderStatus;
import dev.ngb.tenant.resource.application.order.command.CreateOrderUseCase;
import dev.ngb.tenant.resource.application.order.command.ManageOrderUseCase;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.application.order.query.OrderReadRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.support.HttpIntegrationTest;
import dev.ngb.tenant.support.HttpWorld;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A cancel that reaches Billing writes {@code CANCELING} before the remote call, so a Billing outage leaves an order a
 * reaper can finish rather than one that looks untouched while Billing already cancelled the subscription. The reaper
 * retries the idempotent cancel and closes the order.
 */
class OrderCancelPartialStateIntegrationTest extends HttpIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired
    CatalogPlanRepository plans;

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderReadRepository read;

    @Autowired
    ManageOrderUseCase manage;

    @Autowired
    CreateOrderUseCase create;

    @Test
    void aCancelThatBillingRefusesStaysCancelingUntilTheReaperFinishesIt() {
        Order order = subscribedOrder();

        billing.refuseCancel();
        try {
            assertThatThrownBy(() -> manage.cancel(order.id(), order.customerId(), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("billing is down");
        } finally {
            billing.acceptCancel();
        }

        var stuck = read.byId(order.id()).orElseThrow();
        assertThat(stuck.status())
                .as("the observable intermediate state survives the failed remote call")
                .isEqualTo(OrderStatus.CANCELING);
        assertThat(stuck.failureReason()).isEqualTo("billing is down");

        ageTheCancel(order);

        assertThat(create.reapStaleOrders()).isGreaterThanOrEqualTo(1);

        assertThat(read.byId(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(billing.canceledSubscriptions()).contains("sub_1");
    }

    @Test
    void aCancelThatBillingAcceptsClosesTheOrder() {
        Order order = subscribedOrder();

        Order canceled = manage.cancel(order.id(), order.customerId(), true);

        assertThat(canceled.status()).isEqualTo(dev.ngb.tenant.resource.domain.order.OrderStatus.CANCELED);
        assertThat(read.byId(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(billing.canceledSubscriptions()).containsExactly("sub_1");
    }

    /**
     * Pushes the stuck order past the reaper's cutoff. Time passing is what the reaper keys on, and a test cannot wait
     * fifteen minutes, so the row's own update stamp is moved instead.
     */
    private void ageTheCancel(Order order) {
        Order stuck = orders.findById(order.id()).orElseThrow();
        orders.save(stuck.cancelFailed(
                stuck.failureReason(), Instant.now().minus(Duration.ofHours(1))));
    }

    private Order subscribedOrder() {
        User user = registerUser.execute(new RegisterUserUseCase.Command(
                "cancel-" + UUID.randomUUID() + "@example.com", "Ada", HttpWorld.PASSWORD, Role.VIEWER));
        CatalogPlan plan = new CatalogPlan(
                UUID.randomUUID(), "cancel-" + UUID.randomUUID(), "Managed",
                ResourceType.MANAGED_SERVICE, "description", "bplan_1", true, NOW);
        plans.save(plan);
        Order order = create.execute(new CreateOrderUseCase.Command(
                user.id(), plan.id(), "1", null, "cancel-" + UUID.randomUUID()));
        assertThat(order.subscriptionId()).as("a managed-service order carries Billing's subscription").isEqualTo("sub_1");
        return order;
    }
}
