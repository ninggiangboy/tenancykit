package dev.ngb.customer.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import dev.ngb.customer.support.WebhookReceiver;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a Tenant's two endpoints actually receive when one of each of customer's four facts happens.
 *
 * <p>The second endpoint is the point of the case: it is a consumer that customer knows nothing about, and it receives the
 * same deliveries with its own secret, so a new Tenant-side consumer needs no change here. Both are asserted against
 * the committed contracts — the {@code Customer-*} header set, a signature that verifies with that endpoint's own secret,
 * the canonical envelope body, and an {@code order.refunded} that carries the {@code return} block a forwarder needs.
 * A third endpoint belonging to another Tenant receives nothing.
 */
class WebhookDeliveryContractTest extends CustomerApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Every fact customer announces; both endpoints subscribe to all of them. */
    private static final List<String> TYPES =
            List.of("customer.created", "order.recorded", "order.refunded", "customer.erased");

    private static final Set<String> ENVELOPE =
            Set.of("id", "type", "aggregate", "tenantId", "occurredAt", "schemaVersion", "data");

    @Autowired OutboxRelay relay;

    @Autowired DeliverWebhooksUseCase deliver;

    @Autowired CreateEndpointUseCase createEndpoint;

    @Test
    void twoEndpointsOfOneTenantReceiveEachFactOnceAndAnotherTenantsEndpointReceivesNothing() throws Exception {
        Session session = tenant(CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_ERASE, CustomerPermissions.ORDER_RECORD);
        Session rival = tenant(CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_ERASE, CustomerPermissions.ORDER_RECORD);

        try (WebhookReceiver receiver = new WebhookReceiver("/first", "/second", "/rival")) {
            CreateEndpointUseCase.Result first = subscribe(session, receiver.url("/first"), "first consumer");
            CreateEndpointUseCase.Result second = subscribe(session, receiver.url("/second"), "second consumer");
            subscribe(rival, receiver.url("/rival"), "another tenant's consumer");

            String ref = "cust-consumers";
            putAs(session, "/api/v1/customers/{externalRef}", "{\"displayName\":\"Ada Lovelace\"}", ref)
                    .andExpect(status().isCreated());
            putAs(session, "/api/v1/orders/{orderRef}", order(ref), "order-consumers")
                    .andExpect(status().isCreated());
            putAs(
                            session,
                            "/api/v1/orders/{orderRef}/returns/{returnRef}",
                            """
                            {"occurredAt":"%s","lines":[{"lineNo":1,"quantity":"1.000","amount":300}]}
                            """.formatted(past()),
                            "order-consumers",
                            "return-consumers")
                    .andExpect(status().isCreated());
            postAs(session, "/api/v1/customers/{externalRef}/erasure", null, ref)
                    .andExpect(status().isOk());

            OutboxRelay.Result relayed = inTenant(session.tenant(), () -> relay.relayDue());
            assertThat(relayed.delivered()).as("one of each fact reached the webhook consumer").isEqualTo(4);
            deliverUntilDry(session);

            List<WebhookReceiver.Call> firstCalls = receiver.calls("/first");
            List<WebhookReceiver.Call> secondCalls = receiver.calls("/second");
            assertThat(firstCalls).as("the first endpoint receives each fact once").hasSize(4);
            assertThat(secondCalls).as("the second endpoint receives each fact once").hasSize(4);
            assertThat(types(firstCalls)).containsExactlyInAnyOrderElementsOf(TYPES);
            assertThat(types(secondCalls)).containsExactlyInAnyOrderElementsOf(TYPES);

            assertDeliveries(firstCalls, first.signingSecret(), first.endpoint().keyId());
            assertDeliveries(secondCalls, second.signingSecret(), second.endpoint().keyId());

            Map<String, WebhookReceiver.Call> firstById = byEventId(firstCalls);
            Map<String, WebhookReceiver.Call> secondById = byEventId(secondCalls);
            assertThat(secondById.keySet())
                    .as("two endpoints of one tenant are told about the same events")
                    .isEqualTo(firstById.keySet());
            firstById.forEach((eventId, call) -> assertThat(secondById.get(eventId).body())
                    .as("the same event is one document, byte for byte, whichever endpoint receives it")
                    .isEqualTo(call.body()));

            WebhookReceiver.Call refunded = firstCalls.stream()
                    .filter(call -> "order.refunded".equals(call.eventType()))
                    .findFirst()
                    .orElseThrow();
            JsonNode returned = JSON.readTree(refunded.bodyText()).get("data").get("return");
            assertThat(returned).as("a forwarder reads the return from the event alone").isNotNull();
            assertThat(returned.get("returnRef").asString()).isEqualTo("return-consumers");
            assertThat(returned.get("lines").get(0).get("amount").asInt()).isEqualTo(300);

            assertThat(receiver.calls("/rival"))
                    .as("another tenant's endpoint is told nothing about this tenant's customers")
                    .isEmpty();
        }
    }

    /** Every delivery of one endpoint: its six {@code Customer-*} headers, its verified signature and its contract. */
    private void assertDeliveries(List<WebhookReceiver.Call> calls, String secret, String keyId) {
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.eventId()).as("Customer-Event-Id is the envelope's id").isNotBlank();
            assertThat(call.eventType()).isIn(TYPES);
            assertThat(call.keyId()).as("Customer-Key-Id names the key that signed it").isEqualTo(keyId);
            assertThat(Long.parseLong(call.timestamp()))
                    .as("Customer-Timestamp is the instant the signature covers")
                    .isPositive();
            assertThat(Integer.parseInt(call.attempt())).as("Customer-Attempt counts the send").isEqualTo(1);
            assertThat(call.signature())
                    .as("a verifier holding this endpoint's secret accepts the signature")
                    .isEqualTo(new WebhookSigner().sign(secret, Long.parseLong(call.timestamp()), call.body()));

            JsonNode envelope = JSON.readTree(call.bodyText());
            assertThat(names(envelope)).as("the body is the canonical envelope").isEqualTo(ENVELOPE);
            assertThat(envelope.get("id").asString()).isEqualTo(call.eventId());
            assertThat(envelope.get("type").asString()).isEqualTo(call.eventType());
            assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(2);
            assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
            assertThat(schemaOf(call.eventType()).validate(envelope.get("data")))
                    .as("the delivered data matches contracts/events/customer/%s.schema.json", call.eventType())
                    .isEmpty();
        });
    }

    private CreateEndpointUseCase.Result subscribe(Session session, String url, String description) {
        return inTenant(session.tenant(), () -> createEndpoint.execute(
                new CreateEndpointUseCase.Command(url, description, TYPES)));
    }

    private void deliverUntilDry(Session session) {
        for (int round = 0; round < 5; round++) {
            if (inTenant(session.tenant(), () -> deliver.runOnce()).claimed() == 0) {
                return;
            }
        }
    }

    private static List<String> types(List<WebhookReceiver.Call> calls) {
        return calls.stream().map(WebhookReceiver.Call::eventType).toList();
    }

    private static Map<String, WebhookReceiver.Call> byEventId(List<WebhookReceiver.Call> calls) {
        return calls.stream()
                .collect(Collectors.toMap(WebhookReceiver.Call::eventId, Function.identity(), (left, right) -> left));
    }

    private static Schema schemaOf(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = WebhookDeliveryContractTest.class.getResourceAsStream(resource)) {
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
                 "createCustomer":false,"lines":[{"sku":"SKU-1","name":"Item","category":"grocery",
                 "quantity":"2.000","amount":1300}]}
                """.formatted(externalRef, past());
    }

    private static Instant past() {
        return Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
    }
}
