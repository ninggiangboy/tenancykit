package dev.ngb.mailer.email;

import static dev.ngb.mailer_jooq.Tables.EMAIL_BODIES;
import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;
import static dev.ngb.mailer_jooq.Tables.EVENT_INBOX;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.email.application.email.command.HousekeepingUseCase;
import dev.ngb.mailer.support.PostgresContainerConfig;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The retention claim, made true: a body past its TTL and an inbox row past its TTL are deleted, and everything else is
 * left alone.
 *
 * <p>The interesting half is the negative one — the ledger row must survive the body, because that is what keeps a dead
 * delivery answerable and resendable after its content is gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfig.class)
class HousekeepingIntegrationTest {

    @Autowired
    HousekeepingUseCase housekeeping;
    @Autowired
    DSLContext dsl;
    @Autowired
    UnitOfWork unitOfWork;
    @Autowired
    TenantContext tenantContext;
    @Autowired
    Clock clock;

    private TenantId tenant;

    @BeforeEach
    void freshTenant() {
        tenant = new TenantId(UUID.randomUUID());
    }

    @Test
    void anOldBodyIsPurgedAndItsLedgerRowSurvives() {
        UUID deliveryId = insertDelivery("hk-" + UUID.randomUUID(), clock.now().minusSeconds(60 * 60 * 24 * 30));

        HousekeepingUseCase.Purged purged = scoped(() -> housekeeping.runOnce());

        assertThat(purged.bodies()).isEqualTo(1);
        assertThat(scoped(() -> dsl.fetchCount(EMAIL_BODIES, EMAIL_BODIES.DELIVERY_ID.eq(deliveryId)))).isZero();
        // The answer to "was this sent" must outlive the content that was sent.
        assertThat(scoped(() -> dsl.fetchCount(EMAIL_DELIVERIES, EMAIL_DELIVERIES.ID.eq(deliveryId)))).isEqualTo(1);
    }

    @Test
    void aRecentBodyIsKept() {
        UUID deliveryId = insertDelivery("hk-new-" + UUID.randomUUID(), clock.now());

        HousekeepingUseCase.Purged purged = scoped(() -> housekeeping.runOnce());

        assertThat(purged.bodies()).isZero();
        assertThat(scoped(() -> dsl.fetchCount(EMAIL_BODIES, EMAIL_BODIES.DELIVERY_ID.eq(deliveryId)))).isEqualTo(1);
    }

    @Test
    void anOldInboxRowIsPurged() {
        String consumer = "mailer.email-send";
        scoped(() -> dsl.insertInto(EVENT_INBOX)
                .set(EVENT_INBOX.TENANT_ID, tenant.value())
                .set(EVENT_INBOX.CONSUMER, consumer)
                .set(EVENT_INBOX.EVENT_ID, UUID.randomUUID())
                .set(EVENT_INBOX.PROCESSED_AT, clock.now().minusSeconds(60 * 60 * 24 * 90))
                .execute());

        HousekeepingUseCase.Purged purged = scoped(() -> housekeeping.runOnce());

        assertThat(purged.inbox()).isEqualTo(1);
        assertThat(scoped(() -> dsl.fetchCount(EVENT_INBOX))).isZero();
    }

    // --------------------------------------------------------------- helpers

    private UUID insertDelivery(String requestId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        scoped(() -> {
            dsl.insertInto(EMAIL_DELIVERIES)
                    .set(EMAIL_DELIVERIES.ID, id)
                    .set(EMAIL_DELIVERIES.TENANT_ID, tenant.value())
                    .set(EMAIL_DELIVERIES.REQUEST_ID, requestId)
                    .set(EMAIL_DELIVERIES.RECIPIENT, "someone@example.com")
                    .set(EMAIL_DELIVERIES.FROM_KEY, "default")
                    .set(EMAIL_DELIVERIES.SUBJECT, "a subject")
                    .set(EMAIL_DELIVERIES.STATUS, "SUCCEEDED")
                    .set(EMAIL_DELIVERIES.ATTEMPTS, 1)
                    .set(EMAIL_DELIVERIES.CREATED_AT, createdAt)
                    .set(EMAIL_DELIVERIES.COMPLETED_AT, createdAt)
                    .execute();
            return dsl.insertInto(EMAIL_BODIES)
                    .set(EMAIL_BODIES.TENANT_ID, tenant.value())
                    .set(EMAIL_BODIES.DELIVERY_ID, id)
                    .set(EMAIL_BODIES.SUBJECT, "a subject")
                    .set(EMAIL_BODIES.CC, new String[0])
                    .set(EMAIL_BODIES.TEXT_BODY, "Hello")
                    .set(EMAIL_BODIES.CREATED_AT, createdAt)
                    .execute();
        });
        return id;
    }

    private <T> T scoped(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(work));
    }
}
