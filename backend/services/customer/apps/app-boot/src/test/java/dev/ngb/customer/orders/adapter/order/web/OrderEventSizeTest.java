package dev.ngb.customer.orders.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.customer.orders.application.order.exception.LineLimitExceededException;
import dev.ngb.customer.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The largest order customer records, taken all the way out of the process: recorded, relayed through the real outbox,
 * turned into a delivery and sent to a local receiver, whose signature and body are then checked against the contract.
 *
 * <p>It is the test the order line cap exists for. An order is one outbox row and one webhook body, so the cap is what
 * keeps both bounded; this case proves the cap of 500 lines — the schema's own maximum — survives the relay, the
 * delivery and a strict validation of the payload, and it fails if a later change makes one order too large to deliver.
 */
class OrderEventSizeTest extends OrderApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The number of lines the committed contract allows and customer therefore records in one call. */
    private static final int LINES = 500;

    @Autowired
    OutboxRelay relay;

    @Autowired
    DeliverWebhooksUseCase deliver;

    @Autowired
    CreateEndpointUseCase createEndpoint;

    @Test
    void anOrderOfFiveHundredLinesIsDeliveredSignedAndValid() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/orders", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("Customer-Signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("Customer-Timestamp"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Session session = tenant();
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/orders";
            CreateEndpointUseCase.Result endpoint = inTenant(session.tenant(), () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(url, null, List.of("order.recorded"))));

            putOrder(session, "order-max", bulkOrder("cust-max", past(), LINES))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines.length()").value(LINES));

            OutboxRelay.Result relayed = inTenant(session.tenant(), () -> relay.relayDue());
            assertThat(relayed.delivered()).as("the recorded order reached its consumer").isEqualTo(2);
            inTenant(session.tenant(), () -> deliver.runOnce());

            String delivered = body.get();
            assertThat(delivered).as("the whole order arrived at the endpoint").isNotNull();
            assertThat(delivered.length()).as("the delivered body stays within a few hundred kilobytes")
                    .isLessThan(512 * 1024);
            assertThat(signature.get())
                    .as("a verifier with the endpoint's secret accepts the signature")
                    .isEqualTo(new WebhookSigner().sign(
                            endpoint.signingSecret(),
                            Long.parseLong(timestamp.get()),
                            delivered.getBytes(StandardCharsets.UTF_8)));

            JsonNode document = JSON.readTree(delivered);
            JsonNode data = document.get("data");
            assertThat(data.get("lines").size()).as("every line travels in the payload").isEqualTo(LINES);
            assertThat(schema().validate(data))
                    .as("the delivered payload matches the committed schema")
                    .isEmpty();
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void oneLineMoreThanTheCapIsRefusedBeforeAnythingIsWritten() throws Exception {
        Session session = tenant();

        putOrder(session, "order-over-cap", bulkOrder("cust-over-cap", past(), LINES + 1))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value(LineLimitExceededException.CODE));
        assertThat(orderRows(session)).isZero();
        assertThat(outboxRows(session, "order.recorded")).isZero();
    }

    private static Schema schema() {
        String resource = "/events/order.recorded.schema.json";
        try (InputStream stream = OrderEventSizeTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
