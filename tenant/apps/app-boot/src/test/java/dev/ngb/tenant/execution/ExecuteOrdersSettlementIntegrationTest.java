package dev.ngb.tenant.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.tenant.catalog.application.plan.port.CatalogPlanRepository;
import dev.ngb.tenant.catalog.domain.plan.CatalogPlan;
import dev.ngb.tenant.catalog.domain.plan.ResourceType;
import dev.ngb.tenant.execution.api.queue.ExecuteOrders;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.tenant.resource.api.execution.ExecutionQueue;
import dev.ngb.tenant.resource.api.order.OrderStatus;
import dev.ngb.tenant.resource.application.order.command.CreateOrderUseCase;
import dev.ngb.tenant.resource.application.order.query.OrderReadRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.support.HttpIntegrationTest;
import dev.ngb.tenant.support.HttpWorld;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Settling an order that holds a Billing reservation must complete it without releasing anything.
 * Capturing the reservation already returns the unused part of the hold, so a release afterwards only
 * asks Billing to close a hold that no longer exists.
 */
class ExecuteOrdersSettlementIntegrationTest extends HttpIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired
    CatalogPlanRepository plans;

    @Autowired
    CreateOrderUseCase create;

    @Autowired
    OrderReadRepository read;

    @Autowired
    ExecutionQueue queue;

    @Autowired
    ExecuteOrders execute;

    @Test
    void anOrderWithAReservationCompletesWithoutReleasingTheHold() {
        Order order = queuedOrder();
        var queued = read.byId(order.id()).orElseThrow();
        assertThat(queued.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(queued.reservationId()).isEqualTo("res_1");
        assertThat(billing.releasedReservations()).isEmpty();

        execute.run(100);

        assertThat(read.byId(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(billing.chargedReservations()).contains("res_1");
        assertThat(billing.releasedReservations()).isEmpty();
        assertThat(queue.claim(100)).noneMatch(claimed -> claimed.orderId().equals(order.id()));
    }

    private Order queuedOrder() {
        User user = registerUser.execute(new RegisterUserUseCase.Command(
                "settle-" + UUID.randomUUID() + "@example.com", "Ada", HttpWorld.PASSWORD, Role.VIEWER));
        CatalogPlan plan = new CatalogPlan(
                UUID.randomUUID(), "settle-" + UUID.randomUUID(), "Compute",
                ResourceType.COMPUTE, "description", "bplan_1", true, NOW);
        plans.save(plan);

        return create.execute(new CreateOrderUseCase.Command(
                user.id(), plan.id(), "120", null, "settle-" + UUID.randomUUID()));
    }
}
