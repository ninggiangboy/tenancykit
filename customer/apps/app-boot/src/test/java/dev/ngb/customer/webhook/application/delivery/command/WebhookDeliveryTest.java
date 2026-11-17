package dev.ngb.customer.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.customer.support.CustomerIntegrationTest;
import dev.ngb.customer.support.ListQueries;
import dev.ngb.customer.support.TestEvent;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A recorded delivery reaches the endpoint with a signature a verifier holding the endpoint's secret accepts.
 *
 * <p>The receiver is a local HTTP server, and the harness allows insecure targets because that is what a local receiver
 * is. The header set is {@code Crm-*}, so a receiver can tell this app's webhooks apart from another app's.
 */
class WebhookDeliveryTest extends CustomerIntegrationTest {

    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired RecordDeliveriesUseCase recordDeliveries;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired DeliveryQueries deliveries;

    @Test
    void aRecordedDeliveryReachesTheEndpointWithAVerifiableSignature() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> eventId = new AtomicReference<>();
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("Customer-Signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("Customer-Timestamp"));
            eventId.set(exchange.getRequestHeaders().getFirst("Customer-Event-Id"));
            eventType.set(exchange.getRequestHeaders().getFirst("Customer-Event-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            TenantId tenant = new TenantId(UUID.randomUUID());
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
            CreateEndpointUseCase.Result endpoint = inTenant(tenant, () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(url, null, List.of("customer.created"))));

            TestEvent event = TestEvent.customerCreated(tenant);
            int queued = inTenant(tenant, () -> recordDeliveries.handle(event));
            assertThat(queued).as("one subscribed endpoint gets one delivery").isEqualTo(1);

            inTenant(tenant, () -> deliver.runOnce());

            assertThat(signature.get()).as("the receiver got a signature header").isNotBlank();
            assertThat(eventId.get()).isEqualTo(event.eventId().toString());
            assertThat(eventType.get()).isEqualTo("customer.created");
            assertThat(body.get()).contains(event.payload().get("customerId").toString());
            assertThat(signature.get())
                    .as("a verifier with the endpoint's secret accepts the signature")
                    .isEqualTo(new WebhookSigner().sign(
                            endpoint.signingSecret(),
                            Long.parseLong(timestamp.get()),
                            body.get().getBytes(StandardCharsets.UTF_8)));

            var detail = inTenant(tenant, () -> deliveries.get(firstDeliveryId(tenant)));
            assertThat(detail.delivery().status().name()).isEqualTo("SUCCEEDED");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void theSameEventHandledTwiceQueuesNothingNew() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        // The endpoint is never reached: the inbox deduplicates before any send.
        inTenant(tenant, () -> createEndpoint.execute(new CreateEndpointUseCase.Command(
                "https://hooks.example.com/x", null, List.of("customer.created"))));
        TestEvent event = TestEvent.customerCreated(tenant);

        int first = inTenant(tenant, () -> recordDeliveries.handle(event));
        int second = inTenant(tenant, () -> recordDeliveries.handle(event));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the event inbox makes the consumer idempotent").isZero();
    }

    private UUID firstDeliveryId(TenantId tenant) {
        return inTenant(tenant, () -> deliveries.list(ListQueries.of(DeliveryListing.SCHEMA, "limit", "10"))
                .data()
                .get(0)
                .id());
    }
}
