package dev.ngb.booking.webhook.adapter.delivery.web;

import static dev.ngb.booking_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import dev.ngb.booking.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.webhook.WebhookSigner;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole path a fact takes: the outbox row is relayed into one delivery per subscribed endpoint, the sender posts it
 * with the six signed headers, a 500 retries without touching the endpoint that answered, and another tenant's endpoint
 * never appears.
 */
class WebhookDeliveryIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ALL_DAY_HOURS = """
            {"hours":[{"weekday":1,"opens":"00:00","closes":"24:00"},
                       {"weekday":2,"opens":"00:00","closes":"24:00"},
                       {"weekday":3,"opens":"00:00","closes":"24:00"},
                       {"weekday":4,"opens":"00:00","closes":"24:00"},
                       {"weekday":5,"opens":"00:00","closes":"24:00"},
                       {"weekday":6,"opens":"00:00","closes":"24:00"},
                       {"weekday":7,"opens":"00:00","closes":"24:00"}]}
            """;

    @Autowired
    OutboxRelay relay;

    @Autowired
    DeliverWebhooksUseCase deliver;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void twoEndpointsGetOneDeliveryEachAndA500RetriesOnlyItself() throws Exception {
        Received ok = new Received();
        AtomicInteger failures = new AtomicInteger();
        String base = receive(ok, failures);
        Session session = lifecycle();
        String okSecret = endpoint(session, base + "/ok");
        endpoint(session, base + "/fail");

        hold(session, offering(session));

        inTenant(session.tenant(), () -> relay.relayDue());
        assertThat(deliveries(session)).as("one delivery per subscribed endpoint, not one per event").isEqualTo(2);

        DeliverWebhooksUseCase.Round round = inTenant(session.tenant(), deliver::runOnce);
        assertThat(round.claimed()).isEqualTo(2);
        assertThat(round.succeeded()).isEqualTo(1);
        assertThat(round.retrying()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(ok.count()).isEqualTo(1);

        assertThat(ok.eventType()).isEqualTo("booking.held");
        assertThat(ok.eventId()).isNotBlank();
        assertThat(ok.keyId()).startsWith("whk_");
        assertThat(ok.attempt()).isEqualTo("1");
        String expected = new WebhookSigner()
                .sign(okSecret, Long.parseLong(ok.timestamp()), ok.body().getBytes(StandardCharsets.UTF_8));
        assertThat(ok.signature()).isEqualTo(expected);

        inTenant(session.tenant(), deliver::runOnce);
        assertThat(ok.count()).as("a delivered event is not sent again").isEqualTo(1);
        assertThat(failures.get()).as("the retry waits for its backoff").isEqualTo(1);
    }

    @Test
    void anotherTenantsEndpointNeverReceivesTheEvent() throws Exception {
        Received other = new Received();
        String base = receive(other, new AtomicInteger());
        Session first = lifecycle();
        Session second = lifecycle();
        endpoint(second, base + "/ok");
        endpoint(first, base + "/ok");

        hold(first, offering(first));
        inTenant(first.tenant(), () -> relay.relayDue());
        inTenant(first.tenant(), deliver::runOnce);

        assertThat(deliveries(second)).as("the second tenant queued nothing").isZero();
        assertThat(other.count()).as("the second tenant's endpoint was never called").isEqualTo(1);
    }

    private String receive(Received received, AtomicInteger failures) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            received.record(exchange);
            respond(exchange, 200);
        });
        server.createContext("/fail", exchange -> {
            failures.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500);
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private long deliveries(Session session) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(
                        () -> (long) dsl.fetchCount(WEBHOOK_DELIVERIES)));
    }

    private String endpoint(Session session, String url) throws Exception {
        String body = postAs(session, "/system/v1/webhook-endpoints", """
                {"url":"%s","eventTypes":["booking.held"]}
                """.formatted(url))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("secret").asString();
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.WEBHOOK_READ,
                BookingPermissions.WEBHOOK_MANAGE);
    }

    private String offering(Session session) throws Exception {
        String code = "hook-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/offerings", """
                {"code":"%s","name":"Webhook","kind":"INTERVAL","durationMin":60,"slotStep":30,"horizonDays":30}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        UUID resource = resource(session);
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private UUID resource(Session session) throws Exception {
        String code = "hook-res-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Webhook","zone":"UTC"}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }

    private void hold(Session session, String offering) throws Exception {
        Instant start = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(9));
        postAs(session, "/system/v1/bookings", """
                {"offeringId":"%s","customerRef":"cust-hook","start":"%s","durationMinutes":60}
                """.formatted(offering, start))
                .andExpect(status().isCreated());
    }

    /** The last request the receiver saw, which is enough to verify the signature and the headers. */
    private static final class Received {

        private final List<Captured> captured = new CopyOnWriteArrayList<>();

        void record(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(
                    exchange.getRequestHeaders().getFirst("Booking-Event-Id"),
                    exchange.getRequestHeaders().getFirst("Booking-Event-Type"),
                    exchange.getRequestHeaders().getFirst("Booking-Timestamp"),
                    exchange.getRequestHeaders().getFirst("Booking-Key-Id"),
                    exchange.getRequestHeaders().getFirst("Booking-Signature"),
                    exchange.getRequestHeaders().getFirst("Booking-Attempt"),
                    body));
        }

        int count() {
            return captured.size();
        }

        private Captured last() {
            return captured.get(captured.size() - 1);
        }

        String eventId() {
            return last().eventId();
        }

        String eventType() {
            return last().eventType();
        }

        String timestamp() {
            return last().timestamp();
        }

        String keyId() {
            return last().keyId();
        }

        String signature() {
            return last().signature();
        }

        String attempt() {
            return last().attempt();
        }

        String body() {
            return last().body();
        }
    }

    private record Captured(
            String eventId,
            String eventType,
            String timestamp,
            String keyId,
            String signature,
            String attempt,
            String body) {}
}
