package dev.ngb.loyalty.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.loyalty.support.ListQueries;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole path, in one test: a fact written to the outbox inside a transaction is relayed, recorded as a delivery, and
 * sent to the endpoint with a signature a verifier holding the endpoint's secret accepts.
 *
 * <p>The receiver is a local HTTP server, and the harness allows non-public targets because that is what a local
 * receiver is; the shipped default is false. The header set is {@code Loyalty-*}, so a receiver can tell this app's
 * webhooks apart from another app's.
 */
class WebhookDeliveryTest extends LoyaltyIntegrationTest {

    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired RecordDeliveriesUseCase recordDeliveries;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired DeliveryQueries deliveries;
    @Autowired EventPublisher publisher;
    @Autowired OutboxRelay relay;
    @Autowired UnitOfWork unitOfWork;
    @Autowired DSLContext dsl;

    @Test
    void anOutboxRowReachesAnEndpointAsAVerifiedSignedDelivery() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> eventId = new AtomicReference<>();
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> attempt = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer receiver = receiver(exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("Loyalty-Signature"));
            eventId.set(exchange.getRequestHeaders().getFirst("Loyalty-Event-Id"));
            eventType.set(exchange.getRequestHeaders().getFirst("Loyalty-Event-Type"));
            timestamp.set(exchange.getRequestHeaders().getFirst("Loyalty-Timestamp"));
            attempt.set(exchange.getRequestHeaders().getFirst("Loyalty-Attempt"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            TenantId tenant = new TenantId(UUID.randomUUID());
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
            CreateEndpointUseCase.Result endpoint = inTenant(tenant, () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(url, null, List.of("points.earned"))));

            EventEnvelope event = event(tenant, UUID.randomUUID(), "points.earned", Map.of("memberId", UUID.randomUUID().toString(), "points", 25));
            inTenant(tenant, () -> unitOfWork.transactional(() -> {
                publisher.publish(event);
                return null;
            }));
            assertThat(outboxStatus(tenant, event.eventId())).isEqualTo("PENDING");
            inTenant(tenant, () -> relay.relayDue());

            assertThat(outboxStatus(tenant, event.eventId())).as("the relay marked the row delivered").isEqualTo("DELIVERED");
            assertThat(inTenant(tenant, () -> deliveries.list(ListQueries.of(DeliveryListing.SCHEMA)).data()))
                    .as("one subscribed endpoint gets one delivery")
                    .hasSize(1);

            inTenant(tenant, () -> deliver.runOnce());

            assertThat(signature.get()).as("the receiver got a signature header").isNotBlank();
            assertThat(eventId.get()).isEqualTo(event.eventId().toString());
            assertThat(eventType.get()).isEqualTo("points.earned");
            assertThat(attempt.get()).isEqualTo("1");
            assertThat(body.get()).contains(event.payload().get("memberId").toString());
            assertThat(new WebhookSigner().sign(endpoint.signingSecret(), Long.parseLong(timestamp.get()),
                            body.get().getBytes(StandardCharsets.UTF_8)))
                    .as("the delivery verifies with the endpoint's own signing secret")
                    .isEqualTo(signature.get());
            assertThat(inTenant(tenant, () -> deliveries.list(ListQueries.of(DeliveryListing.SCHEMA)).data()))
                    .singleElement()
                    .satisfies(view -> assertThat(view.status().name()).isEqualTo("SUCCEEDED"));
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void theSameEventHandledTwiceQueuesNothingNew() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        // The endpoint is never reached: the inbox deduplicates before any send.
        inTenant(tenant, () -> createEndpoint.execute(new CreateEndpointUseCase.Command(
                "https://hooks.example.com/x", null, List.of("points.earned"))));
        EventEnvelope event = event(tenant, UUID.randomUUID(), "points.earned", Map.of("memberId", UUID.randomUUID().toString()));

        int first = inTenant(tenant, () -> recordDeliveries.handle(event));
        int second = inTenant(tenant, () -> recordDeliveries.handle(event));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the event inbox makes the consumer idempotent").isZero();
    }

    @Test
    void anEventNoEndpointSubscribedToQueuesNothing() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        inTenant(tenant, () -> createEndpoint.execute(new CreateEndpointUseCase.Command(
                "https://hooks.example.com/x", null, List.of("tier.changed"))));

        int queued = inTenant(tenant, () -> recordDeliveries.handle(
                event(tenant, UUID.randomUUID(), "voucher.used", Map.of("memberId", UUID.randomUUID().toString()))));

        assertThat(queued).isZero();
    }

    private String outboxStatus(TenantId tenant, UUID eventId) {
        // The outbox is tenant-scoped: row-level security only lets a row through inside a unit of work, which is
        // where the relay reads it too.
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl
                .fetchOne("select status from outbox where id = ?", eventId)
                .get("status", String.class)));
    }

    static EventEnvelope event(TenantId tenant, UUID eventId, String type, Map<String, Object> payload) {
        return new EventEnvelope(eventId, tenant, type, "member/" + UUID.randomUUID(), Instant.now(), payload);
    }

    /** The receiver both delivery tests use: one handler for {@code /hook}, on a loopback port. */
    @FunctionalInterface
    interface ExchangeHandler {

        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    static HttpServer receiver(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        return server;
    }
}
