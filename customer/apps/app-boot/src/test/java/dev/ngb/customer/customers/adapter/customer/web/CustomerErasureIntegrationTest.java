package dev.ngb.customer.customers.adapter.customer.web;

import static dev.ngb.customer_jooq.Tables.ACTIVITIES;
import static dev.ngb.customer_jooq.Tables.CONSENT_EVENTS;
import static dev.ngb.customer_jooq.Tables.CUSTOMERS;
import static dev.ngb.customer_jooq.Tables.CUSTOMER_TAGS;
import static dev.ngb.customer_jooq.Tables.ERASURE_REQUESTS;
import static dev.ngb.customer_jooq.Tables.ORDERS;
import static dev.ngb.customer_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.PersonalDataColumns;
import dev.ngb.customer.support.WebhookReceiver;
import dev.ngb.customer.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /customers/{externalRef}/erasure}: one call cuts every tie this tenant's customer holds to a person, a retry of
 * that call replays it, and what points at the customer survives as an anonymous record.
 *
 * <p>The cases that decide the design are here. Every column the schema declares personal holds a value before the
 * call and none afterwards, so the classification the erasure tests against is the one {@code PersonalDataColumnsTest}
 * checks against {@code information_schema} — a column added later fails one of the two. The record of the request is
 * keyed by {@code sha256(tenant ‖ ref)} and never by the reference. Twenty parallel retries leave one record, one
 * announcement and one delivery per subscribed endpoint, and the reference starts a new customer afterwards.
 */
class CustomerErasureIntegrationTest extends CustomerApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(CustomerErasureIntegrationTest.class);

    private static final String SAMPLE_NAME = "Zelda Quixote";
    private static final String SAMPLE_PHONE = "+44 20 7946 0958";
    private static final String SAMPLE_EMAIL = "zelda.quixote@example.com";

    @Autowired OutboxRelay relay;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired CreateEndpointUseCase createEndpoint;

    @Test
    void theErasureClearsEveryPersonalColumnAndKeepsTheRecordsThatPointAtTheCustomer() throws Exception {
        Session session = personalTenant();
        String ref = "cust-erase";
        UUID id = wroteAPerson(session, ref);

        String answered = erase(session, ref)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(id.toString()))
                .andExpect(jsonPath("$.outcome").value("ERASED"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Instant erasedAt = Instant.parse(JSON.readTree(answered).get("erasedAt").asString());

        var customer = inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.selectFrom(CUSTOMERS).where(CUSTOMERS.ID.eq(id)).fetchOne()));
        assertThat(customer).as("the row stays, because orders and consent history point at it").isNotNull();
        assertThat(customer.getExternalRef()).as("the reference becomes the anonymous marker")
                .isEqualTo("erased:" + id);
        assertThat(customer.getStatus()).isEqualTo("ERASED");
        assertThat(customer.getVersion()).as("a caller that pinned the customer sees a different one now").isEqualTo(2L);
        assertThat(customer.getDisplayName()).isNull();
        assertThat(customer.getPhone()).isNull();
        assertThat(customer.getPhoneDigits()).isNull();
        assertThat(customer.getEmail()).isNull();
        assertThat(customer.getFields().data()).as("the Tenant's field values are emptied").isEqualTo("{}");

        assertThat(personalColumnsHoldingData(session, id)).as("no personal column holds a value any more").isEmpty();
        assertThat(rowsOf(session, CUSTOMER_TAGS)).as("the customer's tags are gone").isZero();
        assertThat(rowsOf(session, CONSENT_EVENTS))
                .as("the consent history stays as the proof an agreement existed")
                .isEqualTo(2);
        assertThat(rowsOf(session, ACTIVITIES)).as("the activity stays as a typed, timed marker").isEqualTo(1);
        assertThat(rowsOf(session, ORDERS)).as("the order stays as a business record").isEqualTo(1);
        assertThat(rowsOf(session, CUSTOMERS)).isEqualTo(1);
        assertThat(rowsOf(session, ERASURE_REQUESTS)).as("one call, one record of it").isEqualTo(1);

        assertThat(consentSources(session)).as("the free text the Tenant filled is cleared").containsOnlyNulls();
        assertThat(consentEvidence(session)).containsOnlyNulls();
        var activity = inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.selectFrom(ACTIVITIES).fetchOne()));
        assertThat(activity.getPayload().data()).as("the payload the writer sent is emptied").isEqualTo("{}");
        assertThat(activity.getSource()).isNull();
        assertThat(activity.getActivityRef()).isNull();
        assertThat(activity.getType()).as("what happened, not who it happened to, stays").isEqualTo("pos.receipt-printed");

        assertThat(erasureRefHash(session)).as("the record keeps the hash of the reference, not the reference")
                .isEqualTo(sha256Hex(session.tenant().toString() + ref))
                .hasSize(64)
                .isNotEqualTo(ref);
        assertThat(erasureCustomerId(session)).isEqualTo(id);
        assertThat(erasureErasedAt(session))
                .isEqualTo(erasedAt.truncatedTo(ChronoUnit.MILLIS));

        assertThat(outboxRows(session, "customer.erased")).as("the erasure is announced once").isEqualTo(1);
        JsonNode announced = JSON.readTree(payloadOf(session, "customer.erased"));
        assertThat(names(announced))
                .as("the announcement carries customer's id and nothing about the person")
                .containsExactly("customerId");
        assertThat(announced.get("customerId").asString()).isEqualTo(id.toString());
    }

    @Test
    void twentyParallelRetriesAnswerOneErasureAndDeliverItOncePerEndpoint() throws Exception {
        Session session = personalTenant();
        String ref = "cust-parallel";
        UUID id = wroteAPerson(session, ref);

        try (WebhookReceiver receiver = new WebhookReceiver("/one", "/two")) {
            var first = subscribe(session, receiver.url("/one"));
            var second = subscribe(session, receiver.url("/two"));

            List<Answer> answers = retryInParallel(session, ref, 20);

            assertThat(answers).as("every retry is answered, none is an error")
                    .allMatch(answer -> answer.status() == 200);
            assertThat(distinct(answers, "customerId")).as("every retry names the same customer")
                    .containsExactly(id.toString());
            assertThat(distinctMillis(answers, "erasedAt"))
                    .as("every retry names the same instant")
                    .isEqualTo(List.of(erasedAtOf(answers).truncatedTo(ChronoUnit.MILLIS)));
            assertThat(outcomes(answers)).as("exactly one call erased the person and the rest replayed it")
                    .filteredOn("ERASED"::equals)
                    .hasSize(1);
            assertThat(outcomes(answers)).filteredOn("REPLAYED"::equals).hasSize(19);

            assertThat(rowsOf(session, CUSTOMERS)).isEqualTo(1);
            assertThat(rowsOf(session, ERASURE_REQUESTS)).as("twenty retries leave one record").isEqualTo(1);
            assertThat(erasureRefHash(session)).isEqualTo(sha256Hex(session.tenant().toString() + ref));
            assertThat(outboxRows(session, "customer.erased"))
                    .as("the fact is appended once, however many callers raced")
                    .isEqualTo(1);

            inTenant(session.tenant(), () -> relay.relayDue());
            deliverUntilDry(session);

            assertThat(receiver.calls("/one")).as("the first endpoint is told once").hasSize(1);
            assertThat(receiver.calls("/two")).as("the second endpoint is told once").hasSize(1);
            assertThat(rowsOf(session, WEBHOOK_DELIVERIES))
                    .as("one event, two subscribed endpoints, two deliveries")
                    .isEqualTo(2);

            WebhookReceiver.Call onFirst = receiver.calls("/one").get(0);
            WebhookReceiver.Call onSecond = receiver.calls("/two").get(0);
            assertThat(onFirst.eventType()).isEqualTo("customer.erased");
            assertThat(onSecond.eventId())
                    .as("both endpoints of the tenant are told about the same event")
                    .isEqualTo(onFirst.eventId());
            assertThat(first.signingSecret()).isNotEqualTo(second.signingSecret());
            assertThat(onFirst.signature())
                    .as("the first endpoint's own secret verifies its delivery")
                    .isEqualTo(new WebhookSigner()
                            .sign(first.signingSecret(), Long.parseLong(onFirst.timestamp()), onFirst.body()));
            assertThat(onSecond.signature())
                    .as("the second endpoint's own secret verifies its delivery")
                    .isEqualTo(new WebhookSigner()
                            .sign(second.signingSecret(), Long.parseLong(onSecond.timestamp()), onSecond.body()));
        }
    }

    @Test
    void aWriteToAnErasedCustomerIsRefusedWith409() throws Exception {
        Session session = personalTenant();
        String ref = "cust-guarded";
        UUID id = wroteAPerson(session, ref);
        erase(session, ref).andExpect(status().isOk());
        String erased = "erased:" + id;

        postAs(session, "/api/v1/activities", """
                {"externalRef":"%s","type":"pos.receipt-printed","occurredAt":"%s","payload":{"receipt":"R-2"}}
                """.formatted(erased, past()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ERASED"));

        putAs(session, "/api/v1/orders/{orderRef}", """
                {"externalRef":"%s","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos","total":100,
                 "createCustomer":false,"lines":[{"sku":"SKU-9","name":"Item","category":"grocery",
                 "quantity":"1.000","amount":100}]}
                """.formatted(erased, past()), "order-guarded")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ERASED"));

        postAs(session, "/api/v1/customers/{externalRef}/consents", """
                {"topicKey":"marketing_email","status":"GRANTED","occurredAt":"%s","source":"pos"}
                """.formatted(past()), erased)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ERASED"));

        assertThat(rowsOf(session, ACTIVITIES)).isEqualTo(1);
        assertThat(rowsOf(session, ORDERS)).as("the refused order wrote nothing").isEqualTo(1);
        assertThat(rowsOf(session, CONSENT_EVENTS)).isEqualTo(2);
    }

    @Test
    void theSameReferenceStartsANewCustomerWithANewId() throws Exception {
        Session session = personalTenant();
        String ref = "cust-reused";
        UUID erasedId = wroteAPerson(session, ref);
        erase(session, ref).andExpect(status().isOk());

        String created = putCustomer(session, ref, """
                {"displayName":"Ada King","email":"ada.king@example.com"}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.externalRef").value(ref))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        UUID newId = UUID.fromString(JSON.readTree(created).get("id").asString());
        assertThat(newId).as("the reference is free again and starts a new customer").isNotEqualTo(erasedId);
        assertThat(rowsOf(session, CUSTOMERS)).as("the anonymous marker and the new customer are two rows").isEqualTo(2);
        assertThat(outboxRows(session, "customer.created")).isEqualTo(2);
    }

    @Test
    void anotherTenantsErasureCallIs404() throws Exception {
        Session owner = personalTenant();
        Session rival = tenant(CustomerPermissions.CUSTOMER_ERASE);
        String ref = "cust-owner";
        wroteAPerson(owner, ref);

        erase(rival, ref).andExpect(status().isNotFound());

        assertThat(rowsOf(owner, ERASURE_REQUESTS)).as("another tenant's call erases nothing here").isZero();
        assertThat(inTenant(owner.tenant(), () -> unitOfWork.transactional(() -> dsl.fetchCount(
                CUSTOMERS, CUSTOMERS.EXTERNAL_REF.eq(ref)))))
                .as("the customer is untouched")
                .isEqualTo(1);
    }

    /**
     * The logs of the erasure and of an order that names the same customer, swept for the values a person is found by.
     *
     * <p>A log line is a copy of a person's data that outlives the erasure: it reaches the aggregator before anyone can
     * delete the row, so the erasure is only as complete as what the write path never wrote down. The appender is
     * attached to the root logger at the level the application runs at, and a line the test writes itself proves the
     * capture was live before any of the case's calls passed through it.
     */
    @Test
    void noPersonalDataReachesTheLogs() throws Exception {
        Session session = personalTenant();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try {
            log.info("log capture is live for the erasure and the order that names the customer");
            String ref = "cust-logged";
            putCustomer(session, ref, """
                    {"displayName":"%s","phone":"%s","email":"%s"}
                    """.formatted(SAMPLE_NAME, SAMPLE_PHONE, SAMPLE_EMAIL))
                    .andExpect(status().isCreated());
            putAs(session, "/api/v1/orders/{orderRef}", """
                    {"externalRef":"%s","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos",
                     "total":1300,"createCustomer":false,"lines":[{"sku":"SKU-1","name":"Item",
                     "category":"grocery","quantity":"1.000","amount":1300}]}
                    """.formatted(ref, past()), "order-logged")
                    .andExpect(status().isCreated());
            erase(session, ref).andExpect(status().isOk());
        } finally {
            root.detachAppender(captured);
        }

        String log = captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(log).as("the capture has to see something to prove anything").contains("log capture is live");
        for (String sample : List.of(SAMPLE_NAME, "Quixote", SAMPLE_PHONE, "7946", SAMPLE_EMAIL, "zelda.quixote")) {
            assertThat(log).as("the sample value %s never reaches a log line", sample).doesNotContain(sample);
        }
    }

    /**
     * A tenant whose credential holds every code the fixture needs: writing a person (the customer, its field values and
     * tags), recording the consent history, the activity and the order that point at it, and erasing it.
     */
    private Session personalTenant() {
        return tenant(
                CustomerPermissions.CUSTOMER_MANAGE,
                CustomerPermissions.CUSTOMER_READ,
                CustomerPermissions.CUSTOMER_ERASE,
                CustomerPermissions.FIELD_MANAGE,
                CustomerPermissions.ACTIVITY_RECORD,
                CustomerPermissions.ORDER_RECORD);
    }

    /** A customer that holds a value in every column the schema declares personal, plus the records that stay. */
    private UUID wroteAPerson(Session session, String ref) throws Exception {
        defineText(session, "size");
        String created = putCustomer(session, ref, """
                {"displayName":"Ada Lovelace","phone":"+44 20 7946 0958","email":"ada@example.com",
                 "customFields":{"size":"M"},"tags":["vip","newsletter"]}
                """)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(JSON.readTree(created).get("id").asString());

        postAs(session, "/api/v1/consent-topics", "{\"key\":\"marketing_email\",\"label\":\"Marketing\"}")
                .andExpect(status().isCreated());
        consent(session, ref, "GRANTED", "ref-grant", past());
        consent(session, ref, "REVOKED", "ref-revoke", Instant.now().minusSeconds(300));
        postAs(session, "/api/v1/activities", """
                {"externalRef":"%s","type":"pos.receipt-printed","occurredAt":"%s","source":"till-3",
                 "payload":{"receipt":"R-1"},"activityRef":"act-1"}
                """.formatted(ref, past()))
                .andExpect(status().isCreated());
        putAs(session, "/api/v1/orders/{orderRef}", """
                {"externalRef":"%s","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos","total":1300,
                 "createCustomer":false,"lines":[{"sku":"SKU-1","name":"Item","category":"grocery",
                 "quantity":"1.000","amount":1300}]}
                """.formatted(ref, past()), "order-" + ref)
                .andExpect(status().isCreated());

        assertThat(personalColumnsHoldingData(session, id))
                .as("the fixture writes into every column classified as personal")
                .isEqualTo(PersonalDataColumns.personal());
        return id;
    }

    private ResultActions erase(Session session, String externalRef) throws Exception {
        return postAs(session, "/api/v1/customers/{externalRef}/erasure", null, externalRef);
    }

    private void defineText(Session session, String key) throws Exception {
        postAs(session, "/api/v1/custom-fields", """
                {"key":"%s","label":"Size","type":"TEXT"}
                """.formatted(key))
                .andExpect(status().isCreated());
    }

    private void consent(Session session, String ref, String status, String consentRef, Instant occurredAt)
            throws Exception {
        postAs(session, "/api/v1/customers/{externalRef}/consents", """
                {"topicKey":"marketing_email","status":"%s","occurredAt":"%s","source":"till-3",
                 "evidenceRef":"receipt-R-1","consentRef":"%s"}
                """.formatted(status, occurredAt, consentRef), ref)
                .andExpect(status().isCreated());
    }

    private CreateEndpointUseCase.Result subscribe(Session session, String url) {
        return inTenant(session.tenant(), () -> createEndpoint.execute(
                new CreateEndpointUseCase.Command(url, "erasure consumer", List.of("customer.erased"))));
    }

    private List<Answer> retryInParallel(Session session, String ref, int callers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Answer>> answers = new ArrayList<>(callers);
            for (int i = 0; i < callers; i++) {
                Callable<Answer> retry = () -> {
                    start.await();
                    var response = erase(session, ref).andReturn().getResponse();
                    return new Answer(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
                };
                answers.add(pool.submit(retry));
            }
            start.countDown();
            List<Answer> collected = new ArrayList<>(callers);
            for (Future<Answer> answer : answers) {
                collected.add(answer.get(120, TimeUnit.SECONDS));
            }
            return collected;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /** One retry's answer: the status, and the body it answered with. */
    private record Answer(int status, String body) {}

    private static List<String> distinct(List<Answer> answers, String field) {
        Set<String> values = new TreeSet<>();
        answers.forEach(answer -> values.add(bodyOf(answer).get(field).asString()));
        return List.copyOf(values);
    }

    private static List<Instant> distinctMillis(List<Answer> answers, String field) {
        Set<Instant> values = new TreeSet<>();
        answers.forEach(answer ->
                values.add(Instant.parse(bodyOf(answer).get(field).asString()).truncatedTo(ChronoUnit.MILLIS)));
        return List.copyOf(values);
    }

    private static Instant erasedAtOf(List<Answer> answers) {
        return Instant.parse(bodyOf(answers.get(0)).get("erasedAt").asString());
    }

    private static List<String> outcomes(List<Answer> answers) {
        return answers.stream().map(answer -> bodyOf(answer).get("outcome").asString()).toList();
    }

    private static JsonNode bodyOf(Answer answer) {
        return JSON.readTree(answer.body());
    }

    private void deliverUntilDry(Session session) {
        for (int round = 0; round < 5; round++) {
            DeliverWebhooksUseCase.Round sent = inTenant(session.tenant(), () -> deliver.runOnce());
            if (sent.claimed() == 0) {
                return;
            }
        }
    }

    /**
     * The personal columns that still hold a value for one customer: a column of the {@code customers} row is keyed by
     * its own id, every other personal table by {@code customer_id}.
     */
    private Set<String> personalColumnsHoldingData(Session session, UUID customerId) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            Set<String> holding = new TreeSet<>();
            for (String column : PersonalDataColumns.personal()) {
                String table = PersonalDataColumns.table(column);
                String key = table.equals("customers") ? "id" : "customer_id";
                Field<Object> value = DSL.field(DSL.name(PersonalDataColumns.name(column)));
                Condition filled = value.isNotNull().and(value.cast(SQLDataType.VARCHAR).notIn("", "{}", "[]"));
                if (dsl.fetchCount(
                                DSL.table(DSL.name(table)),
                                filled.and(DSL.field(DSL.name(key)).eq(customerId)))
                        > 0) {
                    holding.add(column);
                }
            }
            return holding;
        }));
    }

    private List<String> consentSources(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(CONSENT_EVENTS.SOURCE).from(CONSENT_EVENTS).fetch(CONSENT_EVENTS.SOURCE)));
    }

    private List<String> consentEvidence(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(CONSENT_EVENTS.EVIDENCE_REF).from(CONSENT_EVENTS).fetch(CONSENT_EVENTS.EVIDENCE_REF)));
    }

    private String erasureRefHash(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(ERASURE_REQUESTS.REF_HASH).from(ERASURE_REQUESTS).fetchOne(ERASURE_REQUESTS.REF_HASH)));
    }

    private UUID erasureCustomerId(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(ERASURE_REQUESTS.CUSTOMER_ID).from(ERASURE_REQUESTS)
                        .fetchOne(ERASURE_REQUESTS.CUSTOMER_ID)));
    }

    private Instant erasureErasedAt(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.select(ERASURE_REQUESTS.ERASED_AT)
                .from(ERASURE_REQUESTS)
                .fetchOne(ERASURE_REQUESTS.ERASED_AT)
                .truncatedTo(ChronoUnit.MILLIS)));
    }

    private String payloadOf(Session session, String type) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> dsl.select(dev.ngb.customer_jooq.Tables.OUTBOX.PAYLOAD)
                        .from(dev.ngb.customer_jooq.Tables.OUTBOX)
                        .where(dev.ngb.customer_jooq.Tables.OUTBOX.TYPE.eq(type))
                        .fetchOne(dev.ngb.customer_jooq.Tables.OUTBOX.PAYLOAD)
                        .data()));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this runtime", e);
        }
    }

    private static Instant past() {
        return Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
