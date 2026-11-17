package dev.ngb.customer.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import dev.ngb.customer.support.WebhookReceiver;
import dev.ngb.customer.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four facts customer announces, each taken out of the process and validated against the contract it is committed under.
 *
 * <p>Every case drives the real API, lets the real outbox relay turn the row into a delivery and the real sender deliver
 * it to a local receiver, then reads the document the receiver actually got. The envelope is asserted there rather than
 * in a hand-built map, because the envelope is what a Tenant parses: {@code id} is a UUID v7 (the deduplication key),
 * {@code aggregate} names the customer or the order, {@code schemaVersion} is 2 and {@code data} is validated by the
 * very schema file the build packages from {@code contracts/events/customer}.
 */
class EventContractTest extends CustomerApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> ENVELOPE =
            Set.of("id", "type", "aggregate", "tenantId", "occurredAt", "schemaVersion", "data");

    @Autowired OutboxRelay relay;

    @Autowired DeliverWebhooksUseCase deliver;

    @Autowired CreateEndpointUseCase createEndpoint;

    @Test
    void customerCreatedCarriesTheDocumentedPayloadAndEnvelope() throws Exception {
        Session session = tenant(CustomerPermissions.CUSTOMER_MANAGE);

        WebhookReceiver.Call call = deliverOne(session, "customer.created", () -> putAs(
                        session,
                        "/api/v1/customers/{externalRef}",
                        "{\"displayName\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}",
                        "cust-contract")
                .andExpect(status().isCreated()));

        JsonNode envelope = JSON.readTree(call.bodyText());
        assertEnvelope(envelope, call, session, "customer.created");
        JsonNode data = envelope.get("data");
        assertThat(names(data)).containsExactlyInAnyOrder("customerId", "externalRef", "createdAt");
        assertThat(data.get("externalRef").asString()).isEqualTo("cust-contract");
        assertThat(envelope.get("aggregate").asString()).isEqualTo("customer/" + data.get("customerId").asString());
    }

    @Test
    void customerErasedCarriesTheCustomersIdAndNothingElse() throws Exception {
        Session session = tenant(CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_ERASE);
        putAs(session, "/api/v1/customers/{externalRef}", """
                {"displayName":"Ada Lovelace","phone":"+44 20 7946 0958","email":"ada@example.com"}
                """, "cust-contract-erased")
                .andExpect(status().isCreated());

        WebhookReceiver.Call call = deliverOne(
                session,
                "customer.erased",
                () -> postAs(session, "/api/v1/customers/{externalRef}/erasure", null, "cust-contract-erased")
                        .andExpect(status().isOk()));

        JsonNode envelope = JSON.readTree(call.bodyText());
        assertEnvelope(envelope, call, session, "customer.erased");
        JsonNode data = envelope.get("data");
        assertThat(names(data))
                .as("an erasure announces the id it erased, and customer no longer holds anything else")
                .containsExactly("customerId");
        assertThat(envelope.get("aggregate").asString()).isEqualTo("customer/" + data.get("customerId").asString());
    }

    @Test
    void orderRecordedCarriesTheOrdersWholeStateUnderTheOrderAggregate() throws Exception {
        Session session = tenant(CustomerPermissions.ORDER_RECORD);

        WebhookReceiver.Call call = deliverOne(session, "order.recorded", () -> putAs(
                        session, "/api/v1/orders/{orderRef}", order("cust-contract-order"), "order-contract")
                .andExpect(status().isCreated()));

        JsonNode envelope = JSON.readTree(call.bodyText());
        assertEnvelope(envelope, call, session, "order.recorded");
        JsonNode data = envelope.get("data");
        assertThat(data.get("orderRef").asString()).isEqualTo("order-contract");
        assertThat(data.get("orderVersion").asInt()).isEqualTo(1);
        assertThat(data.get("lines").size()).isEqualTo(1);
        assertThat(data.has("return")).as("an order that nobody returned carries no return block").isFalse();
        assertThat(envelope.get("aggregate").asString()).isEqualTo("order/order-contract");
    }

    @Test
    void orderRefundedCarriesTheReturnThatCausedIt() throws Exception {
        Session session = tenant(CustomerPermissions.ORDER_RECORD);
        putAs(session, "/api/v1/orders/{orderRef}", order("cust-contract-refund"), "order-contract-refund")
                .andExpect(status().isCreated());

        WebhookReceiver.Call call = deliverOne(
                session,
                "order.refunded",
                () -> putAs(
                                session,
                                "/api/v1/orders/{orderRef}/returns/{returnRef}",
                                """
                                {"occurredAt":"%s","lines":[{"lineNo":1,"quantity":"1.000","amount":1300}]}
                                """.formatted(past()),
                                "order-contract-refund",
                                "return-contract")
                        .andExpect(status().isCreated()));

        JsonNode envelope = JSON.readTree(call.bodyText());
        assertEnvelope(envelope, call, session, "order.refunded");
        JsonNode data = envelope.get("data");
        assertThat(data.get("orderVersion").asInt()).as("the return raised the version").isEqualTo(2);
        JsonNode returned = data.get("return");
        assertThat(returned).as("a forwarder can read the return from the event alone").isNotNull();
        assertThat(names(returned)).containsExactlyInAnyOrder("returnRef", "occurredAt", "lines");
        assertThat(returned.get("returnRef").asString()).isEqualTo("return-contract");
        assertThat(returned.get("lines").get(0).get("lineNo").asInt()).isEqualTo(1);
        assertThat(envelope.get("aggregate").asString()).isEqualTo("order/order-contract-refund");
    }

    /**
     * Drives one event through the real API and out to a local receiver: one endpoint subscribed to that type, one relay
     * round, one sender round. Nothing is stubbed, so the bytes validated here are the bytes a Tenant receives.
     */
    private WebhookReceiver.Call deliverOne(Session session, String type, Drive drive) throws Exception {
        try (WebhookReceiver receiver = new WebhookReceiver("/hook")) {
            inTenant(session.tenant(), () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(receiver.url("/hook"), "contract consumer", List.of(type))));

            drive.run();

            OutboxRelay.Result relayed = inTenant(session.tenant(), () -> relay.relayDue());
            assertThat(relayed.delivered()).as("the outbox row reached the webhook consumer").isGreaterThan(0);
            for (int round = 0; round < 5; round++) {
                if (inTenant(session.tenant(), () -> deliver.runOnce()).claimed() == 0) {
                    break;
                }
            }

            assertThat(receiver.all())
                    .as("one event and one subscribed endpoint make one delivery")
                    .hasSize(1);
            return receiver.all().get(0);
        }
    }

    private void assertEnvelope(JsonNode envelope, WebhookReceiver.Call call, Session session, String type) {
        assertThat(names(envelope)).as("the body is the one canonical envelope, a webhook and a broker message alike")
                .isEqualTo(ENVELOPE);
        assertThat(envelope.get("type").asString()).isEqualTo(type);
        assertThat(envelope.get("schemaVersion").asInt())
                .as("the envelope version every producer emits")
                .isEqualTo(2);
        assertThat(envelope.get("tenantId").asString())
                .as("the envelope names the tenant the fact belongs to")
                .isEqualTo(session.tenant().value().toString());
        UUID id = UUID.fromString(envelope.get("id").asString());
        assertThat(id.version()).as("id is a UUID v7, the deduplication key").isEqualTo(7);
        assertThat(call.eventId()).as("the header and the field are the same id").isEqualTo(id.toString());
        assertThat(call.eventType()).isEqualTo(type);
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
        assertThat(envelope.get("aggregate").asString()).isNotBlank();
        assertThat(schemaOf(type).validate(envelope.get("data")))
                .as("data matches contracts/events/customer/%s.schema.json", type)
                .isEmpty();
    }

    /** The committed schema, read from the copy the build packages beside the compiled classes. */
    private static Schema schemaOf(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = EventContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static String order(String externalRef) {
        return """
                {"externalRef":"%s","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos","total":1300,
                 "createCustomer":true,"lines":[{"sku":"SKU-1","name":"Item","category":"grocery",
                 "quantity":"1.000","amount":1300}]}
                """.formatted(externalRef, past());
    }

    private static Instant past() {
        return Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
    }

    /** The API call that produces the event the case is about; it may throw because a request may fail. */
    @FunctionalInterface
    private interface Drive {
        void run() throws Exception;
    }
}
