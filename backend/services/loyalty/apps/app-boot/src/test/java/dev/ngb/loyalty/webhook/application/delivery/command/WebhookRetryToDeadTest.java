package dev.ngb.loyalty.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.ListQueries;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.loyalty.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A receiver that keeps failing exhausts the attempt budget: the delivery goes {@code DEAD}, keeps its history, and a
 * retry queues a fresh attempt of the same stored body rather than rewriting the dead one.
 */
class WebhookRetryToDeadTest extends LoyaltyIntegrationTest {

    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired RecordDeliveriesUseCase recordDeliveries;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired ManageDeliveryUseCase manageDelivery;
    @Autowired DeliveryQueries deliveries;

    @DynamicPropertySource
    static void retryPolicy(DynamicPropertyRegistry registry) {
        // Two attempts and a negligible backoff, so the test does not wait for the production schedule.
        registry.add("loyalty.webhooks.max-attempts", () -> "2");
        registry.add("loyalty.webhooks.backoff-base", () -> "PT0.001S");
        registry.add("loyalty.webhooks.backoff-cap", () -> "PT0.001S");
    }

    @Test
    void aFailingReceiverExhaustsTheAttemptsAndAReplayQueuesAnother() throws Exception {
        HttpServer receiver = WebhookDeliveryTest.receiver(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        receiver.start();
        try {
            TenantId tenant = new TenantId(UUID.randomUUID());
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
            inTenant(tenant, () -> createEndpoint.execute(
                    new CreateEndpointUseCase.Command(url, null, List.of("points.earned"))));
            inTenant(tenant, () -> recordDeliveries.handle(WebhookDeliveryTest.event(
                    tenant, UUID.randomUUID(), "points.earned", java.util.Map.of("memberId", UUID.randomUUID().toString()))));

            deliverUntilDead(tenant);

            WebhookDeliveryView dead = onlyDelivery(tenant);
            assertThat(dead.status().name()).isEqualTo("DEAD");
            assertThat(dead.attempts()).isEqualTo(2);
            assertThat(dead.lastStatusCode()).isEqualTo(500);

            inTenant(tenant, () -> manageDelivery.retry(new dev.ngb.loyalty.webhook.domain.delivery.WebhookDeliveryId(dead.id())));

            assertThat(inTenant(tenant, () -> deliveries.list(ListQueries.of(DeliveryListing.SCHEMA)).data()))
                    .as("the original stays as evidence and the retry is its own delivery")
                    .hasSize(2)
                    .anySatisfy(view -> {
                        assertThat(view.status().name()).isEqualTo("PENDING");
                        assertThat(view.eventId()).isEqualTo(dead.eventId());
                    });
        } finally {
            receiver.stop(0);
        }
    }

    private void deliverUntilDead(TenantId tenant) throws InterruptedException {
        for (int round = 0; round < 5; round++) {
            inTenant(tenant, () -> deliver.runOnce());
            if ("DEAD".equals(onlyDelivery(tenant).status().name())) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private WebhookDeliveryView onlyDelivery(TenantId tenant) {
        return inTenant(tenant, () -> deliveries.list(ListQueries.of(DeliveryListing.SCHEMA)).data().get(0));
    }
}
