package dev.ngb.loyalty.points.application.expiry.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.EventContracts;
import dev.ngb.loyalty.support.EventContracts.Delivery;
import dev.ngb.loyalty.support.ExpiryApiTest;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.WebhookReceiver;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the notice run tells the Tenant, and once only.
 *
 * <p>The notice changes nothing a member holds: it records that a lot was announced and queues one event per member for
 * everything that came into the program's window since the last run. The cases below state that a lot is announced once
 * however often the run repeats or two instances run together, that a member's newly due lots arrive in one event, that
 * a window of zero announces nothing, and that a lot already spent or expired is never announced.
 */
class ExpiryNoticeIntegrationTest extends ExpiryApiTest {

    private static final EarnVectors.Program THIRTY_DAYS = program(30);
    private static final EarnVectors.Program TEN_DAYS = program(10);
    private static final EarnVectors.Program FIVE_DAYS = program(5);
    private static final EarnVectors.Program NO_NOTICE = program(0);

    @Autowired OutboxRelay relay;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired CreateEndpointUseCase endpoints;

    private static EarnVectors.Program program(int noticeDays) {
        return new EarnVectors.Program("USD", 100L, "UTC", 12, noticeDays, 12);
    }

    @Test
    void eachLotIsAnnouncedOnceAcrossRepeatedRuns() {
        TenantId tenant = tenant();
        seedProgram(tenant, THIRTY_DAYS);
        Instant now = Instant.now();
        seedLot(tenant, "M-1", 20L, now.plus(10, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 15L, now.plus(20, ChronoUnit.DAYS));

        NoticeExpiringPointsUseCase.Result first = runNotice(tenant);
        NoticeExpiringPointsUseCase.Result second = runNotice(tenant);

        assertThat(first.lots()).isEqualTo(2);
        assertThat(first.points()).isEqualTo(35);
        assertThat(second.lots()).as("an announced lot is not announced again").isZero();
        assertThat(noticedLots(tenant, "M-1")).isEqualTo(2);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expiring'"))
                .as("one event per member per run")
                .isEqualTo(1);
        assertThat(available(tenant, "M-1")).as("the notice changes no balance").isEqualTo(35);
    }

    @Test
    void twoInstancesAtOnceAnnounceEachLotOnce() {
        TenantId tenant = tenant();
        seedProgram(tenant, THIRTY_DAYS);
        seedLot(tenant, "M-1", 20L, Instant.now().plus(10, ChronoUnit.DAYS));

        ParallelCalls.run(2, 2, index -> runNotice(tenant));

        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expiring'"))
                .as("both passes serialise on the member and only one announces")
                .isEqualTo(1);
        assertThat(noticedLots(tenant, "M-1")).isEqualTo(1);
    }

    @Test
    void aRaisedWindowAnnouncesTheNewlyDueLotsInOneEventAndALoweredOneChangesNothing() {
        TenantId tenant = tenant();
        seedProgram(tenant, TEN_DAYS);
        Instant now = Instant.now();
        seedLot(tenant, "M-1", 10L, now.plus(5, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 7L, now.plus(15, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 15L, now.plus(20, ChronoUnit.DAYS));

        runNotice(tenant);
        assertThat(noticedLots(tenant, "M-1")).as("only the lot inside the ten-day window").isEqualTo(1);

        // Raising the window brings the two later lots in; the next run announces both, in one event for the member.
        seedProgram(tenant, THIRTY_DAYS);
        NoticeExpiringPointsUseCase.Result raised = runNotice(tenant);

        assertThat(raised.lots()).isEqualTo(2);
        assertThat(raised.points()).isEqualTo(22);
        assertThat(noticedLots(tenant, "M-1")).isEqualTo(3);
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expiring'")).isEqualTo(2);

        // Lowering it again announces nothing new: what was announced stays announced.
        seedProgram(tenant, FIVE_DAYS);
        assertThat(runNotice(tenant).lots()).isZero();
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.expiring'")).isEqualTo(2);
    }

    @Test
    void aProgramWithoutANoticeWindowAnnouncesNothing() {
        TenantId tenant = tenant();
        seedProgram(tenant, NO_NOTICE);
        seedLot(tenant, "M-1", 20L, Instant.now().plus(5, ChronoUnit.DAYS));

        NoticeExpiringPointsUseCase.Result result = runNotice(tenant);

        assertThat(result.lots()).isZero();
        assertThat(noticedLots(tenant, "M-1")).isZero();
        assertThat(scalar(tenant, "select count(*) from outbox")).isZero();
    }

    @Test
    void aSpentOrAlreadyExpiredLotIsNeverAnnounced() {
        TenantId tenant = tenant();
        seedProgram(tenant, THIRTY_DAYS);
        Instant now = Instant.now();
        seedLot(tenant, "M-1", 10L, 0L, now.plus(10, ChronoUnit.DAYS));
        seedLot(tenant, "M-1", 15L, now.minus(1, ChronoUnit.DAYS));
        UUID live = seedLot(tenant, "M-1", 20L, now.plus(10, ChronoUnit.DAYS));

        NoticeExpiringPointsUseCase.Result result = runNotice(tenant);

        assertThat(result.lots()).as("the spent lot and the expired one are left alone").isEqualTo(1);
        assertThat(result.points()).isEqualTo(20);
        assertThat(noticedLots(tenant, "M-1")).isEqualTo(1);
        assertThat(inTenant(tenant, () -> unitOfWork.transactional(() -> dsl
                        .fetchOne("select id from point_lots where expiry_noticed_at is not null")
                        .get(0, UUID.class))))
                .isEqualTo(live);
    }

    @Test
    void theNoticeReachesEachSubscribedEndpointOnce() {
        TenantId tenant = tenant();
        seedProgram(tenant, THIRTY_DAYS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant early = now.plus(10, ChronoUnit.DAYS);
        seedLot(tenant, "M-1", 20L, early);
        seedLot(tenant, "M-1", 15L, now.plus(20, ChronoUnit.DAYS));
        String member = memberId(tenant, "M-1").toString();
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook", "/second")) {
            receiver.subscribe(tenant, "/hook", PointsEvents.POINTS_EXPIRING);
            receiver.subscribe(tenant, "/second", PointsEvents.POINTS_EXPIRING);

            runNotice(tenant);
            deliverAll(tenant);

            assertThat(receiver.deliveries()).as("one delivery per subscribed endpoint").hasSize(2);
            Delivery delivery = receiver.only(PointsEvents.POINTS_EXPIRING);
            assertThat(receiver.deliveries()).extracting(Delivery::eventId).containsOnly(delivery.eventId());
            var data = delivery.data();
            assertThat(data.get("memberId").asString()).isEqualTo(member);
            assertThat(data.get("externalRef").asString()).isEqualTo("M-1");
            assertThat(data.get("points").asLong()).isEqualTo(35);
            assertThat(data.get("lots")).hasSize(2);
            assertThat(Instant.parse(data.get("earliestExpiresAt").asString())).isEqualTo(early);
            assertThat(Instant.parse(data.get("lots").get(0).get("expiresAt").asString()))
                    .as("the lots arrive in expiry order")
                    .isEqualTo(early);
            assertThat(data.get("noticeDays").asInt()).isEqualTo(30);
            assertThat(data.get("available").asLong()).isEqualTo(35);
            EventContracts.assertConforms(PointsEvents.POINTS_EXPIRING, data);
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);

            // A repeated run announces nothing, so nothing is delivered again.
            runNotice(tenant);
            deliverAll(tenant);
            assertThat(receiver.deliveries()).hasSize(2);
        }
    }

    @Test
    void aNoticeRunThatRollsBackAnnouncesNothing() {
        TenantId tenant = tenant();
        seedProgram(tenant, THIRTY_DAYS);
        seedLot(tenant, "M-1", 20L, Instant.now().plus(10, ChronoUnit.DAYS));

        assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(() -> {
            noticeExpiring.runOnce();
            throw new IllegalStateException("the run could not be committed");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(noticedLots(tenant, "M-1")).as("the notice went back with the transaction").isZero();
        assertThat(scalar(tenant, "select count(*) from outbox")).isZero();

        // The same run outside a rollback announces the lot, so the silence above is the rollback's.
        assertThat(runNotice(tenant).lots()).isEqualTo(1);
        assertThat(noticedLots(tenant, "M-1")).isEqualTo(1);
    }

    private void deliverAll(TenantId tenant) {
        inTenant(tenant, () -> relay.relayDue());
        inTenant(tenant, () -> deliver.runOnce());
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }
}
