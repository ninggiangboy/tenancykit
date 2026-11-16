package dev.ngb.loyalty.contract;

import static dev.ngb.loyalty_jooq.Tables.EVENT_INBOX;
import static dev.ngb.loyalty_jooq.Tables.OUTBOX;
import static dev.ngb.loyalty_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.boot.config.LoyaltySettings;
import dev.ngb.loyalty.boot.jobs.MessagingHousekeeping;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.PersonalDataColumns;
import dev.ngb.loyalty.support.PointsApiTest;
import dev.ngb.loyalty.support.WebhookReceiver;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every column of loyalty's schema that could hold a person is classified, and the ones that are not cleared by an erasure
 * are removed with their row.
 *
 * <p>This test is the guard the schema needs: a column added later cannot arrive without a decision, because
 * {@code information_schema} is read and compared with the list in both directions — an unclassified column fails, and so
 * does a classification naming a column the schema no longer has. A column in {@code [PERSONAL]} is then asserted empty
 * after an erasure by {@code MemberErasureIntegrationTest}, which reads the same list; a column in {@code [PURGED]} is
 * proved here to leave with its row when the housekeeping retention passes.
 */
class PersonalDataColumnsTest extends PointsApiTest {

    /** The tables the housekeeping job owns, and the only ones a purged column may live in. */
    private static final Set<String> HOUSEKEEPING_TABLES =
            Set.of("outbox", "event_inbox", "webhook_deliveries", "webhook_delivery_attempts");

    @Autowired DSLContext dsl;
    @Autowired UnitOfWork unitOfWork;
    @Autowired Clock clock;
    @Autowired LoyaltySettings settings;
    @Autowired OutboxRelay relay;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired PurgeDeliveriesUseCase purge;
    @Autowired CreateEndpointUseCase endpoints;

    @Test
    void everyTextJsonbAndArrayColumnOfTheSchemaIsClassified() {
        Set<String> columns = textJsonbAndArrayColumns();

        assertThat(columns)
                .as("a column that can hold text arrived without a decision about what it may hold")
                .isNotEmpty()
                .isSubsetOf(PersonalDataColumns.all());
        assertThat(PersonalDataColumns.all())
                .as("a classification names a column the schema no longer has")
                .isSubsetOf(columns);
    }

    @Test
    void aPersonalColumnIsOneAnErasureRewritesAndAPurgedOneIsOneHousekeepingRemoves() {
        assertThat(PersonalDataColumns.personalTables())
                .as("an erasure rewrites the member, its vouchers and the Tenant's references on its spends")
                .containsExactlyInAnyOrder("members", "vouchers", "redemptions");

        assertThat(PersonalDataColumns.purged())
                .as("a purged column is one the housekeeping job deletes the row of")
                .allSatisfy(column -> assertThat(HOUSEKEEPING_TABLES).contains(PersonalDataColumns.table(column)));
        assertThat(PersonalDataColumns.purged())
                .as("the payload of a message and a delivery body are both purged")
                .contains("outbox.payload", "webhook_deliveries.body");
    }

    /**
     * A {@code member.enrolled} payload leaves every table a message of it can reach: the relayed outbox row, the inbox row
     * that deduplicated it and the delivery body a receiver was sent.
     *
     * <p>The retention is lowered to zero for this case — a test that waited a week would not run — while the default the
     * deployment ships is asserted first, so the setting the docs promise is the one being shortened. The outbox and the
     * inbox are the copy's own deletes; the delivery body is the delivery half of the same job, which is where a body is
     * kept.
     */
    @Test
    void aMemberEnrolledPayloadLeavesTheOutboxTheInboxAndTheDeliveryAfterTheRetention() {
        assertThat(settings.housekeeping().relayedOutboxRetention())
                .as("a relayed outbox row keeps its payload for a week by default")
                .isEqualTo(Duration.ofDays(7));

        TenantId tenant = new TenantId(UUID.randomUUID());
        String ref = "M-housekeeping";
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook")) {
            receiver.subscribe(tenant, "/hook", PointsEvents.MEMBER_ENROLLED);

            assertThat(putMember(tenant, ref, "Ada Lovelace").status()).isEqualTo(201);
            assertThat(outboxPayloads(tenant))
                    .as("the announcement holds the Tenant's reference before the retention passes")
                    .hasSize(1)
                    .allSatisfy(payload -> assertThat(payload).contains(ref));

            inTenant(tenant, () -> relay.relayDue());
            deliverUntilDry(tenant);
            assertThat(receiver.deliveries()).as("the delivery carried the same body").hasSize(1);
            assertThat(receiver.deliveries().getFirst().body()).contains(ref);
            assertThat(inboxRows(tenant)).as("the consumer deduplicated it").isEqualTo(1);
            assertThat(deliveryBodies(tenant)).hasSize(1).allSatisfy(body -> assertThat(body).contains(ref));

            // The delivery is aged rather than the retention shortened, so the deployment's own retention is the one the
            // purge applies; a test cannot wait it out, but it can make the row older than it.
            int aged = inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                    "update webhook_deliveries set completed_at = completed_at - interval '400 days'")));
            assertThat(aged).as("the finished delivery could be aged past the retention").isEqualTo(1);

            MessagingHousekeeping housekeeping =
                    new MessagingHousekeeping(unitOfWork, dsl, loweredRetention(), clock);
            MessagingHousekeeping.Purged purged = inTenant(tenant, () -> housekeeping.runOnce(tenant));

            assertThat(purged.relayed()).as("the relayed outbox row's payload is deleted with the row").isEqualTo(1);
            assertThat(purged.inbox()).as("the inbox row's deduplication state is deleted with the row").isEqualTo(1);
            assertThat(outboxPayloads(tenant)).as("no outbox row holds the payload any more").isEmpty();
            assertThat(inboxRows(tenant)).as("no inbox row holds the event id any more").isZero();

            int purgedBodies = inTenant(tenant, () -> purge.execute(settings.housekeeping().batchSize()));

            assertThat(purgedBodies).as("the finished delivery is purged with its body").isEqualTo(1);
            assertThat(deliveryBodies(tenant)).as("no delivery holds the body any more").isEmpty();
        }
    }

    /** Every text, jsonb and array column of the schema the application connects to. */
    private Set<String> textJsonbAndArrayColumns() {
        return inTenant(new TenantId(UUID.randomUUID()), () -> unitOfWork.transactional(() -> new TreeSet<>(dsl.fetch(
                                """
                                select table_name || '.' || column_name
                                from information_schema.columns
                                where table_schema = current_schema()
                                  and data_type in ('text', 'character varying', 'jsonb', 'ARRAY')
                                """)
                        .getValues(0, String.class))));
    }

    private List<String> outboxPayloads(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.select(OUTBOX.PAYLOAD)
                .from(OUTBOX)
                .fetch(OUTBOX.PAYLOAD)
                .stream()
                .map(payload -> payload.data())
                .toList()));
    }

    private long inboxRows(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.fetchCount(EVENT_INBOX)));
    }

    private List<String> deliveryBodies(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.select(WEBHOOK_DELIVERIES.BODY).from(WEBHOOK_DELIVERIES).fetch(WEBHOOK_DELIVERIES.BODY)));
    }

    private void deliverUntilDry(TenantId tenant) {
        for (int round = 0; round < 5; round++) {
            DeliverWebhooksUseCase.Round sent = inTenant(tenant, () -> deliver.runOnce());
            if (sent.claimed() == 0) {
                return;
            }
        }
    }

    /** The deployment's housekeeping settings with every retention shortened to nothing, which is what a test can wait. */
    private static LoyaltySettings.Housekeeping loweredRetention() {
        return new LoyaltySettings.Housekeeping(
                false, Duration.ZERO, Duration.ZERO, Duration.ZERO, 500, Duration.ofHours(1));
    }
}
