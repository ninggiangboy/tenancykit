package dev.ngb.tenant.resource.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.tenant.resource.api.order.PricingModel;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final Instant CREATED = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-11T01:00:00Z");
    private static final IdGenerator IDS = () -> ORDER_ID;

    private static final Map<OrderStatus, Set<OrderStatus>> DOCUMENTED = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.QUEUED, OrderStatus.FAILED, OrderStatus.CANCELED),
            OrderStatus.QUEUED, Set.of(OrderStatus.RUNNING, OrderStatus.SUSPENDED, OrderStatus.FAILED, OrderStatus.CANCELED),
            OrderStatus.RUNNING, Set.of(
                    OrderStatus.QUEUED, OrderStatus.COMPLETED, OrderStatus.SUSPENDED, OrderStatus.FAILED, OrderStatus.CANCELED),
            OrderStatus.SUSPENDED, Set.of(OrderStatus.QUEUED, OrderStatus.FAILED, OrderStatus.CANCELED),
            OrderStatus.COMPLETED, Set.of(),
            OrderStatus.FAILED, Set.of(),
            OrderStatus.CANCELED, Set.of());

    private static Order pending() {
        return Order.pending(
                IDS,
                CUSTOMER_ID,
                "cus_1",
                "wal_1",
                PLAN_ID,
                "bplan_1",
                PricingModel.TIME_BASED,
                "compute_hours",
                "10",
                "hour",
                null,
                "USD",
                "ORD-" + ORDER_ID,
                CREATED);
    }

    private static Order in(OrderStatus status) {
        Order pending = pending();
        return switch (status) {
            case PENDING -> pending;
            case QUEUED -> pending.queued(500L, "res_1", null, LATER);
            case RUNNING -> pending.queued(500L, "res_1", null, LATER).running(LATER);
            case COMPLETED -> pending.queued(500L, "res_1", null, LATER).running(LATER).completed(LATER);
            case FAILED -> pending.failed("wallet empty", LATER);
            case SUSPENDED -> pending.queued(500L, "res_1", null, LATER).running(LATER).suspended(LATER);
            case CANCELED -> pending.canceled(LATER);
        };
    }

    @Test
    void aNewOrderIsPendingWithTheReferenceAndNoReservation() {
        Order order = pending();

        assertThat(order.id()).isEqualTo(ORDER_ID);
        assertThat(order.reference()).isEqualTo("ORD-" + ORDER_ID);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.estimatedMinorUnits()).isZero();
        assertThat(order.reservationId()).isNull();
        assertThat(order.subscriptionId()).isNull();
        assertThat(order.failureReason()).isNull();
        assertThat(order.createdAt()).isEqualTo(CREATED);
        assertThat(order.updatedAt()).isEqualTo(CREATED);
        assertThat(order.terminal()).isFalse();
    }

    @Test
    void confirmingAPendingOrderRecordsTheReservationAndTheEstimate() {
        Order queued = pending().queued(500L, "res_1", "sub_1", LATER);

        assertThat(queued.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(queued.estimatedMinorUnits()).isEqualTo(500L);
        assertThat(queued.reservationId()).isEqualTo("res_1");
        assertThat(queued.subscriptionId()).isEqualTo("sub_1");
        assertThat(queued.updatedAt()).isEqualTo(LATER);
        assertThat(queued).usingRecursiveComparison()
                .ignoringFields("status", "estimatedMinorUnits", "reservationId", "subscriptionId", "updatedAt")
                .isEqualTo(pending());
    }

    @Test
    void aTransitionKeepsEveryOtherFieldAndMovesOnlyTheUpdateTime() {
        Order order = in(OrderStatus.QUEUED);

        Order running = order.running(LATER);

        assertThat(running.status()).isEqualTo(OrderStatus.RUNNING);
        assertThat(running.updatedAt()).isEqualTo(LATER);
        assertThat(running).usingRecursiveComparison()
                .ignoringFields("status", "updatedAt")
                .isEqualTo(order);
    }

    @Test
    void onlyFailureRecordsAReason() {
        Order failed = pending().failed("wallet empty", LATER);
        Order suspended = in(OrderStatus.RUNNING).suspended(LATER);

        assertThat(failed.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("wallet empty");
        assertThat(suspended.status()).isEqualTo(OrderStatus.SUSPENDED);
        assertThat(suspended.failureReason()).isNull();
    }

    @Test
    void aWorkerRequeuesARunningOrderAndAWebhookResumesASuspendedOne() {
        Order requeued = in(OrderStatus.RUNNING).requeued(LATER);
        Order resumed = in(OrderStatus.SUSPENDED).resumed(LATER);

        assertThat(requeued.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(resumed.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(requeued.failureReason()).isNull();
        assertThat(requeued.terminal()).isFalse();
    }

    @Test
    void aRequeueIsNotAResume() {
        assertThatThrownBy(() -> in(OrderStatus.RUNNING).resumed(LATER))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> in(OrderStatus.SUSPENDED).requeued(LATER))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aRunningOrderCannotBeQueuedAgainAndAQueuedOrderCannotComplete() {
        assertThatThrownBy(() -> in(OrderStatus.RUNNING).queued(500L, "res_1", null, LATER))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> in(OrderStatus.QUEUED).completed(LATER))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void completedFailedAndCanceledAreTerminal() {
        assertThat(in(OrderStatus.COMPLETED).terminal()).isTrue();
        assertThat(in(OrderStatus.FAILED).terminal()).isTrue();
        assertThat(in(OrderStatus.CANCELED).terminal()).isTrue();
        assertThat(in(OrderStatus.PENDING).terminal()).isFalse();
        assertThat(in(OrderStatus.SUSPENDED).terminal()).isFalse();
    }

    @Test
    void aTerminalOrderRejectsEveryFurtherTransition() {
        Order completed = in(OrderStatus.COMPLETED);

        assertThatThrownBy(() -> completed.running(LATER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> completed.failed("late", LATER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> completed.suspended(LATER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> completed.resumed(LATER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> completed.requeued(LATER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> completed.canceled(LATER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(ORDER_ID.toString())
                .hasMessageContaining("COMPLETED");
        assertThatThrownBy(() -> in(OrderStatus.CANCELED).completed(LATER)).isInstanceOf(BadRequestException.class);
    }

    static Stream<Arguments> everyOrderedPair() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyOrderedPair")
    void theMapHoldsEveryDocumentedEdgeAndNothingElse(OrderStatus from, OrderStatus to) {
        boolean documented = from != to && DOCUMENTED.getOrDefault(from, Set.of()).contains(to);

        assertThat(Order.allows(from, to)).isEqualTo(documented);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyOrderedPair")
    void everyMoveFollowsTheMap(OrderStatus from, OrderStatus to) {
        if (to == OrderStatus.PENDING) {
            assertThat(Order.allows(from, to)).isFalse();
            return;
        }
        boolean documented = from != to && DOCUMENTED.getOrDefault(from, Set.of()).contains(to);

        if (documented) {
            assertThat(move(in(from), to).status()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> move(in(from), to))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    private static Order move(Order order, OrderStatus to) {
        return switch (to) {
            case PENDING -> throw new IllegalStateException("no transition returns to PENDING");
            case QUEUED -> order.status() == OrderStatus.RUNNING
                    ? order.requeued(LATER)
                    : order.status() == OrderStatus.SUSPENDED
                            ? order.resumed(LATER)
                            : order.queued(500L, "res_1", null, LATER);
            case RUNNING -> order.running(LATER);
            case COMPLETED -> order.completed(LATER);
            case FAILED -> order.failed("late", LATER);
            case SUSPENDED -> order.suspended(LATER);
            case CANCELED -> order.canceled(LATER);
        };
    }
}
