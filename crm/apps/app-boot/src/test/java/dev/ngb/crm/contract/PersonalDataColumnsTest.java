package dev.ngb.crm.contract;

import static dev.ngb.crm_jooq.Tables.EVENT_INBOX;
import static dev.ngb.crm_jooq.Tables.OUTBOX;
import static dev.ngb.crm_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.Clock;
import dev.ngb.crm.boot.config.CrmSettings;
import dev.ngb.crm.boot.jobs.MessagingHousekeeping;
import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.support.CrmApiTest;
import dev.ngb.crm.support.PersonalDataColumns;
import dev.ngb.crm.support.WebhookReceiver;
import dev.ngb.crm.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.crm.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import dev.ngb.crm.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every column of crm's schema that could hold a person is classified, and the ones that are not cleared by an erasure
 * are removed with their row.
 *
 * <p>This test is the guard the plan names: a column added later cannot arrive without a decision, because
 * {@code information_schema} is read and compared with the list in both directions — an unclassified column fails, and
 * so does a classification naming a column the schema no longer has. A column in {@code [PERSONAL]} is then asserted
 * empty after an erasure by {@code CustomerErasureIntegrationTest}, which reads the same list; a column in
 * {@code [PURGED]} is proved here to leave with its row when the housekeeping retention passes.
 */
class PersonalDataColumnsTest extends CrmApiTest {

    /** The tables the housekeeping job owns, and the only ones a purged column may live in. */
    private static final Set<String> HOUSEKEEPING_TABLES =
            Set.of("outbox", "event_inbox", "webhook_deliveries", "webhook_delivery_attempts");

    @Autowired CrmSettings settings;

    @Autowired Clock clock;

    @Autowired OutboxRelay relay;

    @Autowired DeliverWebhooksUseCase deliver;

    @Autowired PurgeDeliveriesUseCase purge;

    @Autowired CreateEndpointUseCase createEndpoint;

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
                .as("an erasure rewrites the customer, its tags, its consent history and its activities")
                .containsExactlyInAnyOrder("customers", "customer_tags", "consent_events", "activities");

        assertThat(PersonalDataColumns.purged())
                .as("a purged column is one the housekeeping job deletes the row of")
                .allSatisfy(column -> assertThat(HOUSEKEEPING_TABLES).contains(PersonalDataColumns.table(column)));
        assertThat(PersonalDataColumns.purged())
                .as("the payload of a message, a delivery body and the errors an attempt keeps are all purged")
                .contains("outbox.payload", "webhook_deliveries.body", "webhook_delivery_attempts.response_excerpt");
    }

    /**
     * A {@code customer.created} payload leaves all three tables a message of it can reach: the relayed outbox row, the
     * inbox row that deduplicated it and the delivery body a receiver was sent.
     *
     * <p>The retention is lowered to zero for this case — a test that waited seven days would not run — while the
     * default the deployment ships is asserted first, so the setting the docs promise is the one being shortened. The
     * outbox and the inbox are the copy's own deletes; the delivery body is the delivery half of the same job, which is
     * where a body is kept.
     */
    @Test
    void aCustomerCreatedPayloadLeavesTheOutboxTheInboxAndTheDeliveryAfterTheRetention() throws Exception {
        assertThat(settings.housekeeping().relayedOutboxRetention())
                .as("a relayed outbox row keeps its payload for a week by default")
                .isEqualTo(Duration.ofDays(7));

        Session session = tenant(CrmPermissions.CUSTOMER_MANAGE);
        String ref = "cust-housekeeping";
        try (WebhookReceiver receiver = new WebhookReceiver("/hook")) {
            inTenant(session.tenant(), () -> createEndpoint.execute(new CreateEndpointUseCase.Command(
                    receiver.url("/hook"), "housekeeping consumer", List.of("customer.created"))));

            putAs(session, "/api/v1/customers/{externalRef}", "{\"displayName\":\"Ada Lovelace\"}", ref)
                    .andExpect(status().isCreated());
            assertThat(outboxPayloads(session))
                    .as("the announcement holds the Tenant's reference before the retention passes")
                    .hasSize(1)
                    .allSatisfy(payload -> assertThat(payload).contains(ref));

            inTenant(session.tenant(), () -> relay.relayDue());
            for (int round = 0; round < 5; round++) {
                if (inTenant(session.tenant(), () -> deliver.runOnce()).claimed() == 0) {
                    break;
                }
            }
            assertThat(receiver.all()).as("the delivery carried the same body").hasSize(1);
            assertThat(receiver.all().get(0).bodyText()).contains(ref);
            assertThat(inboxRows(session)).as("the consumer deduplicated it").isEqualTo(1);
            assertThat(deliveryBodies(session)).hasSize(1).allSatisfy(body -> assertThat(body).contains(ref));

            MessagingHousekeeping housekeeping =
                    new MessagingHousekeeping(unitOfWork, dsl, loweredRetention(), clock);
            MessagingHousekeeping.Purged purged = inTenant(session.tenant(), () -> housekeeping.runOnce(session.tenant()));

            assertThat(purged.relayed()).as("the relayed outbox row's payload is deleted with the row").isEqualTo(1);
            assertThat(purged.inbox()).as("the inbox row's deduplication state is deleted with the row").isEqualTo(1);
            assertThat(outboxPayloads(session)).as("no outbox row holds the payload any more").isEmpty();
            assertThat(inboxRows(session)).as("no inbox row holds the event id any more").isZero();

            int purgedBodies = inTenant(session.tenant(), () -> purge.runOnce(clock.now()
                    .plus(settings.housekeeping().webhookDeliveryRetention())
                    .plus(Duration.ofMinutes(1))));
            assertThat(purgedBodies).as("the finished delivery is purged with its body").isEqualTo(1);
            assertThat(deliveryBodies(session)).as("no delivery holds the body any more").isEmpty();
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

    private List<String> outboxPayloads(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.select(OUTBOX.PAYLOAD)
                .from(OUTBOX)
                .fetch(OUTBOX.PAYLOAD)
                .stream()
                .map(payload -> payload.data())
                .toList()));
    }

    private long inboxRows(Session session) {
        return rowsOf(session, EVENT_INBOX);
    }

    private List<String> deliveryBodies(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(WEBHOOK_DELIVERIES.BODY).from(WEBHOOK_DELIVERIES).fetch(WEBHOOK_DELIVERIES.BODY)));
    }

    /** The deployment's housekeeping settings with every retention shortened to nothing, which is what a test can wait. */
    private CrmSettings.Housekeeping loweredRetention() {
        CrmSettings.Housekeeping shipped = settings.housekeeping();
        return new CrmSettings.Housekeeping(
                false, Duration.ZERO, Duration.ZERO, Duration.ZERO, shipped.webhookDeliveryRetention(),
                shipped.batchSize(), shipped.interval());
    }
}
