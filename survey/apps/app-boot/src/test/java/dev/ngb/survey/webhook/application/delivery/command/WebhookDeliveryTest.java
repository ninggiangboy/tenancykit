package dev.ngb.survey.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.survey.collection.domain.response.Response;
import dev.ngb.survey.collection.domain.response.ResponseEvents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.survey.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A recorded delivery reaches the endpoint with a signature a verifier holding the endpoint's secret accepts.
 *
 * <p>The receiver is a local HTTP server, and the harness allows insecure targets because that is what a local receiver
 * is. The header set is {@code Survey-*}, so a receiver can tell this app's webhooks apart from another app's.
 */
class WebhookDeliveryTest extends SurveyIntegrationTest {

    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired RecordDeliveriesUseCase recordDeliveries;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired DeliveryQueries deliveries;

    @Test
    void aRecordedDeliveryReachesTheEndpointWithAVerifiableSignature() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> eventId = new AtomicReference<>();
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("Survey-Signature"));
            eventId.set(exchange.getRequestHeaders().getFirst("Survey-Event-Id"));
            eventType.set(exchange.getRequestHeaders().getFirst("Survey-Event-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            TenantId tenant = new TenantId(UUID.randomUUID());
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
            CreateEndpointUseCase.Result endpoint = inTenant(tenant, () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(url, null, List.of("survey-response.submitted"))));

            ResponseEvents.ResponseSubmitted event = submittedEvent(tenant);
            int queued = inTenant(tenant, () -> recordDeliveries.handle(event));
            assertThat(queued).as("one subscribed endpoint gets one delivery").isEqualTo(1);

            inTenant(tenant, () -> deliver.runOnce());

            assertThat(signature.get()).as("the receiver got a signature header").isNotBlank();
            assertThat(eventId.get()).isEqualTo(event.eventId().toString());
            assertThat(eventType.get()).isEqualTo("survey-response.submitted");
            assertThat(body.get()).contains(event.payload().get("response_id").toString());
            assertThat(body.get()).as("no answer rides on the event").doesNotContain("answers");

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
                "https://hooks.example.com/x", null, List.of("survey-response.submitted"))));
        ResponseEvents.ResponseSubmitted event = submittedEvent(tenant);

        int first = inTenant(tenant, () -> recordDeliveries.handle(event));
        int second = inTenant(tenant, () -> recordDeliveries.handle(event));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the event inbox makes the consumer idempotent").isZero();
    }

    private UUID firstDeliveryId(TenantId tenant) {
        return inTenant(tenant, () -> deliveries.list(null, null, null, 10).get(0).id());
    }

    private static ResponseEvents.ResponseSubmitted submittedEvent(TenantId tenant) {
        Instant now = Instant.now();
        Response response = Response.draft(
                        UUID.randomUUID(),
                        tenant.value(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "en",
                        "qr",
                        null,
                        null,
                        now)
                .completed(Map.of("q1", "secret-answer"), Map.of(), new java.math.BigDecimal("3"), null, "key", now);
        return ResponseEvents.submitted(UUID.randomUUID(), response);
    }
}
