package dev.ngb.crm.webhook.application.delivery.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.crm.support.CrmIntegrationTest;
import dev.ngb.crm.support.TestEvent;
import dev.ngb.crm.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole path in one test: a business transaction writes an outbox row, the relay hands it to the webhook context
 * in the same transaction, and one round of the sender delivers it to a local receiver.
 *
 * <p>It is the skeleton's proof that the outbox has a consumer: a relay with no listener would mark the row delivered
 * and no request would ever leave the process.
 */
class OutboxToWebhookIntegrationTest extends CrmIntegrationTest {

    @Autowired EventPublisher events;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OutboxRelay relay;
    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired DeliverWebhooksUseCase deliver;

    @Test
    void anOutboxRowBecomesASignedDelivery() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("Crm-Signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("Crm-Timestamp"));
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
            inTenant(tenant, () -> unitOfWork.transactional(() -> events.publish(event)));

            OutboxRelay.Result relayed = inTenant(tenant, () -> relay.relayDue());
            assertThat(relayed.delivered()).as("the outbox row reached its consumer").isEqualTo(1);

            inTenant(tenant, () -> deliver.runOnce());

            assertThat(body.get()).contains(event.payload().get("customerId").toString());
            assertThat(signature.get())
                    .as("a verifier with the endpoint's secret accepts the signature")
                    .isEqualTo(new WebhookSigner().sign(
                            endpoint.signingSecret(),
                            Long.parseLong(timestamp.get()),
                            body.get().getBytes(StandardCharsets.UTF_8)));
        } finally {
            receiver.stop(0);
        }
    }
}
