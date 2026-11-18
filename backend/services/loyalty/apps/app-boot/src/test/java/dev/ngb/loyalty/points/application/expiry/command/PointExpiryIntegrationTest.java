package dev.ngb.loyalty.points.application.expiry.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.loyalty.boot.infrastructure.lease.JobLease;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.support.EventContracts;
import dev.ngb.loyalty.support.EventContracts.Delivery;
import dev.ngb.loyalty.support.ExpiryApiTest;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.WebhookReceiver;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the expiry run does to a member's lots.
 *
 * <p>Nothing here changes what a member can spend: an expired lot already counts for nothing, so the run only makes the
 * ledger say so. The cases below state that, and then state the properties the run has to keep — each lot written off
 * once, in expiry order, never one that was already spent, and never a debt.
 */
class PointExpiryIntegrationTest extends ExpiryApiTest {

    @Autowired OutboxRelay relay;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired CreateEndpointUseCase endpoints;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void eachDueLotIsWrittenOffForWhatItStillHeldAndASpentLotIsNeverTouched() {
        TenantId tenant = tenant();
        Instant now = Instant.now();
        UUID spent = seedLot(tenant, "M-1", 5L, 0L, now.minus(4, ChronoUnit.DAYS));
        UUID earliest = seedLot(tenant, "M-1", 20L, now.minus(3, ChronoUnit.DAYS));
        UUID partlySpent = seedLot(tenant, "M-1", 15L, 5L, now.minus(2, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 10L, now.minus(1, ChronoUnit.DAYS));
        assertThat(available(tenant, "M-1")).as("every lot has already expired").isZero();

        ExpirePointsUseCase.Result result = runExpiry(tenant);

        assertThat(result.lots()).as("the spent lot had nothing left to write off").isEqualTo(3);
        assertThat(result.points()).as("twenty, the five a clawback left, and ten").isEqualTo(35);
        assertThat(allocationsOf(tenant, spent)).as("a spent lot is never expired").isZero();
        assertThat(allocationsOf(tenant, earliest)).isEqualTo(1);
        assertThat(writtenOffPoints(tenant, earliest)).isEqualTo(20);
        assertThat(writtenOffPoints(tenant, partlySpent))
                .as("a lot another flow partly spent is written off for what it still held")
                .isEqualTo(5);
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isZero();
        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'EXPIRE'"))
                .isEqualTo(35);
        assertThat(available(tenant, "M-1")).as("the run changes no balance").isZero();
        assertThat(debt(tenant, "M-1")).as("expiry never touches the debt").isZero();
        long listed = 0L;
        for (var entry : getLedger(tenant, "M-1", 50).json().get("data")) {
            if ("EXPIRE".equals(entry.get("kind").asString())) {
                listed += entry.get("points").asLong();
            }
        }
        assertThat(listed).as("the member's ledger lists the write-offs").isEqualTo(35);
    }

    @Test
    void runningTwiceWritesEachExpireOnce() {
        TenantId tenant = tenant();
        Instant now = Instant.now();
        seedLot(tenant, "M-1", 20L, now.minus(2, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 30L, now.minus(1, ChronoUnit.DAYS));

        runExpiry(tenant);
        ExpirePointsUseCase.Result second = runExpiry(tenant);

        assertThat(second.lots()).as("a second run finds nothing left to write off").isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'EXPIRE'")).isEqualTo(2);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expired'"))
                .as("one event per member per expiry transaction")
                .isEqualTo(1);
    }

    @Test
    void twoInstancesAtOnceWriteEachExpireOnce() {
        TenantId tenant = tenant();
        Instant now = Instant.now();
        seedLot(tenant, "M-1", 20L, now.minus(2, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 30L, now.minus(1, ChronoUnit.DAYS));

        ParallelCalls.run(2, 2, index -> runExpiry(tenant));

        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'EXPIRE'"))
                .as("the two passes serialise on the member and write each lot once")
                .isEqualTo(2);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expired'"))
                .as("only the pass that found work announced it")
                .isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from point_lots where remaining < 0")).isZero();
    }

    @Test
    void aLeaseLetsOneInstanceRunASlice() {
        JobLease first = new JobLease(transactionManager, jdbc);
        JobLease second = new JobLease(transactionManager, jdbc);
        String slice = "point-expiry/" + UUID.randomUUID();

        assertThat(first.tryAcquire(slice, Duration.ofMinutes(10))).isTrue();
        assertThat(second.tryAcquire(slice, Duration.ofMinutes(10)))
                .as("the slice belongs to the instance that claimed it")
                .isFalse();
        assertThat(first.renew(slice, Duration.ofMinutes(10))).isTrue();

        first.release(slice);
        assertThat(second.tryAcquire(slice, Duration.ofMinutes(10))).as("a released slice is free again").isTrue();
        second.release(slice);
    }

    @Test
    void aMemberWithOnlyExpiredLotsReportsZeroAndOwesNothing() {
        TenantId tenant = tenant();
        seedLot(tenant, "M-1", 30L, Instant.now().minus(1, ChronoUnit.DAYS));

        runExpiry(tenant);

        ApiResponse member = getMember(tenant, "M-1");
        assertThat(member.status()).isEqualTo(200);
        assertThat(member.json().get("available").asLong()).isZero();
        assertThat(member.json().get("debt").asLong()).isZero();
        assertThat(member.json().get("pointsExpiring")).isEmpty();
        assertThat(postRedemption(tenant, "M-1", "K-1", 1, "RECEIPT-1").code())
                .as("expiring points are not a debt")
                .isEqualTo("INSUFFICIENT_POINTS");
    }

    @Test
    void whatTheMemberSeesExpiringIsWhatTheRunLaterWritesOff() {
        TenantId tenant = tenant();
        UUID lot = seedLot(tenant, "M-1", 20L, Instant.now().plus(10, ChronoUnit.DAYS));

        var announced = getMember(tenant, "M-1").json().get("pointsExpiring");
        assertThat(announced).hasSize(1);
        long announcedPoints = announced.get(0).get("points").asLong();
        assertThat(announcedPoints).isEqualTo(20);

        // The clock cannot be moved, so the lot is moved past its own expiry, which is the state the run acts on.
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "update point_lots set expires_at = now() - interval '1 second' where id = ?", lot)));
        runExpiry(tenant);

        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'EXPIRE'"))
                .as("the run wrote off exactly what the member was told would expire")
                .isEqualTo(announcedPoints);
        assertThat(getMember(tenant, "M-1").json().get("pointsExpiring")).isEmpty();
    }

    @Test
    void theWriteOffReachesEachSubscribedEndpointOnceInExpiryOrder() {
        TenantId tenant = tenant();
        Instant now = Instant.now();
        UUID later = seedLot(tenant, "M-1", 5L, now.minus(1, ChronoUnit.DAYS));
        UUID sooner = seedLot(tenant, "M-1", 20L, now.minus(2, ChronoUnit.DAYS));
        String member = memberId(tenant, "M-1").toString();
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook", "/second")) {
            receiver.subscribe(tenant, "/hook", PointsEvents.POINTS_EXPIRED);
            receiver.subscribe(tenant, "/second", PointsEvents.POINTS_EXPIRED);

            runExpiry(tenant);
            deliverAll(tenant);

            assertThat(receiver.deliveries()).as("one delivery per subscribed endpoint").hasSize(2);
            assertThat(receiver.deliveries()).extracting(Delivery::path).containsExactlyInAnyOrder("/hook", "/second");
            Delivery delivery = receiver.only(PointsEvents.POINTS_EXPIRED);
            assertThat(receiver.deliveries()).extracting(Delivery::eventId)
                    .as("the endpoints read the same event")
                    .containsOnly(delivery.eventId());
            var data = delivery.data();
            assertThat(data.get("memberId").asString()).isEqualTo(member);
            assertThat(data.get("externalRef").asString()).isEqualTo("M-1");
            assertThat(data.get("points").asLong()).isEqualTo(25);
            assertThat(data.get("lots")).hasSize(2);
            assertThat(data.get("lots").get(0).get("lotId").asString())
                    .as("the lots arrive earliest expiry first")
                    .isEqualTo(sooner.toString());
            assertThat(data.get("lots").get(1).get("lotId").asString()).isEqualTo(later.toString());
            assertThat(data.get("lots").get(0).get("points").asLong()).isEqualTo(20);
            assertThat(data.get("available").asLong()).isZero();
            assertThat(data.get("debt").asLong()).isZero();
            EventContracts.assertConforms(PointsEvents.POINTS_EXPIRED, data);
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);

            // A second run finds nothing due, so nothing is announced and nothing is delivered again.
            runExpiry(tenant);
            deliverAll(tenant);
            assertThat(receiver.deliveries()).as("a repeated run changes no fact").hasSize(2);
        }
    }

    @Test
    void aRunThatRollsBackAnnouncesNothing() {
        TenantId tenant = tenant();
        seedLot(tenant, "M-1", 40L, Instant.now().minus(1, ChronoUnit.DAYS));

        Supplier<Void> failing = () -> {
            expirePoints.runOnce();
            throw new IllegalStateException("the run could not be committed");
        };
        assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(failing)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'EXPIRE'"))
                .as("the entries went back with the transaction")
                .isZero();
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isEqualTo(40);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expired'")).isZero();

        // The same run outside a rollback does write the entry and announce it, so the silence above is the rollback's.
        assertThat(runExpiry(tenant).lots()).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expired'")).isEqualTo(1);
    }

    private void deliverAll(TenantId tenant) {
        inTenant(tenant, () -> relay.relayDue());
        inTenant(tenant, () -> deliver.runOnce());
    }

    private long allocationsOf(TenantId tenant, UUID lotId) {
        return scalar(tenant, "select count(*) from lot_allocations where lot_id = ?", lotId);
    }

    private long writtenOffPoints(TenantId tenant, UUID lotId) {
        return scalar(
                tenant,
                "select coalesce(sum(l.points), 0) from ledger_entries l join lot_allocations a on a.entry_id = l.id "
                        + "where l.kind = 'EXPIRE' and a.lot_id = ?",
                lotId);
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }
}
