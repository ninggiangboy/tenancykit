package dev.ngb.mailer.email;

import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.support.PostgresContainerConfig;
import dev.ngb.mailer.support.RabbitContainerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer, end to end: a real broker, a real database, the real listener container from the worker profile.
 *
 * <p>Every assertion is a **count**, never "no exception was thrown". The three cases that matter are the ones a naive
 * implementation gets wrong: a replayed event must write nothing, a repeated {@code requestId} under a live row must
 * change nothing, and a repeated {@code requestId} under a dead row must resurrect it instead of being dropped in
 * silence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("worker")
@Import({PostgresContainerConfig.class, RabbitContainerConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true"})
class EmailCommandConsumerIntegrationTest {

    private static final String EXCHANGE = "mailer.commands";
    private static final String ROUTING_KEY = "email.send";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final JsonMapper json = new JsonMapper();

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    DSLContext dsl;

    @Autowired
    UnitOfWork unitOfWork;

    @Autowired
    TenantContext tenantContext;

    private TenantId tenant;

    @BeforeEach
    void freshTenant() {
        tenant = new TenantId(UUID.randomUUID());
    }

    @Test
    void oneCommandWritesOneLedgerRowPerRecipient() {
        publish(envelope(UUID.randomUUID(), "request-1", List.of("a@example.com", "b@example.com")));

        awaitCount("request-1", 2);
        assertThat(statuses("request-1")).containsOnly(DeliveryStatus.PENDING.name());
    }

    @Test
    void aReplayedEventWritesNothing() {
        UUID eventId = UUID.randomUUID();
        byte[] body = envelope(eventId, "request-2", List.of("a@example.com"));

        publish(body);
        awaitCount("request-2", 1);
        publish(body);
        settle();

        assertThat(count("request-2")).isEqualTo(1);
    }

    @Test
    void aRepeatedRequestUnderALiveRowChangesNothing() {
        publish(envelope(UUID.randomUUID(), "request-3", List.of("a@example.com")));
        awaitCount("request-3", 1);
        markSucceeded("request-3");

        // A producer that timed out and retried sends a new event id with the same request id.
        publish(envelope(UUID.randomUUID(), "request-3", List.of("a@example.com")));
        settle();

        assertThat(count("request-3")).isEqualTo(1);
        assertThat(statuses("request-3")).containsOnly(DeliveryStatus.SUCCEEDED.name());
    }

    @Test
    void aRepeatedRequestUnderADeadRowResurrectsIt() {
        publish(envelope(UUID.randomUUID(), "request-4", List.of("a@example.com")));
        awaitCount("request-4", 1);
        markDead("request-4");

        publish(envelope(UUID.randomUUID(), "request-4", List.of("a@example.com")));

        // The row must come back with a fresh budget: without this the plan's promise that a dead delivery can be
        // requested again is false, and the producer's retry is dropped in silence.
        awaitStatus("request-4", DeliveryStatus.PENDING.name());
        assertThat(attempts("request-4")).isZero();
        assertThat(count("request-4")).isEqualTo(1);
    }

    @Test
    void aSchemaInvalidCommandWritesNothingAndDeadLetters() {
        Map<String, Object> data = new LinkedHashMap<>(validData("request-5", List.of("a@example.com")));
        data.remove("subject");

        publish(envelope(UUID.randomUUID(), data));
        Message deadLettered = rabbit.receive(EXCHANGE + ".dlq", 20_000);

        assertThat(deadLettered).as("the invalid command must reach the dead-letter queue").isNotNull();
        assertThat(count("request-5")).isZero();
    }

    @Test
    void aCommandAddressingThePlatformTenantIsAccepted() {
        TenantId platform = RequestScope.PLATFORM_TENANT_ID;
        TenantId saved = tenant;
        tenant = platform;
        try {
            // Notification notifies Platform administrators about their own access changes, so a platform-tenant
            // command is a legitimate send, not a mistake.
            publish(envelope(UUID.randomUUID(), "request-6", List.of("a@example.com")));
            awaitCount("request-6", 1);
            // The ledger row is written under the Platform tenant, which is where the delivery sweep finds it.
            assertThat(count("request-6")).isEqualTo(1);
            assertThat(rabbit.receive(EXCHANGE + ".dlq", 500))
                    .as("nothing was dead-lettered")
                    .isNull();
        } finally {
            tenant = saved;
        }
    }

    // --------------------------------------------------------------- helpers

    private void publish(byte[] body) {
        rabbit.send(EXCHANGE, ROUTING_KEY, new Message(body));
    }

    private byte[] envelope(UUID eventId, String requestId, List<String> to) {
        return envelope(eventId, validData(requestId, to));
    }

    private byte[] envelope(UUID eventId, Map<String, Object> data) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", eventId.toString());
        wire.put("type", ROUTING_KEY);
        wire.put("aggregate", "email/" + UUID.randomUUID());
        wire.put("tenantId", tenant.value().toString());
        wire.put("occurredAt", Instant.now().toString());
        // The envelope version is 2; a wrong value rejects every command before the handler sees it.
        wire.put("schemaVersion", 2);
        wire.put("data", data);
        return json.writeValueAsBytes(wire);
    }

    private static Map<String, Object> validData(String requestId, List<String> to) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("to", to);
        data.put("fromKey", "default");
        data.put("fromName", "TenancyKit");
        data.put("subject", "Your invoice");
        data.put("text", "Hello");
        return data;
    }

    private void awaitCount(String requestId, int expected) {
        await(() -> count(requestId) == expected, "expected " + expected + " row(s) for " + requestId);
    }

    private void awaitStatus(String requestId, String expected) {
        await(() -> statuses(requestId).contains(expected), "expected status " + expected + " for " + requestId);
    }

    private static void await(Supplier<Boolean> condition, String description) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            sleep();
        }
        throw new AssertionError("timed out: " + description);
    }

    /** Gives the listener a moment to finish a redelivery that must not change anything. */
    private static void settle() {
        sleep();
        sleep();
        sleep();
    }

    private static void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private int count(String requestId) {
        return scoped(() -> dsl.fetchCount(EMAIL_DELIVERIES, EMAIL_DELIVERIES.REQUEST_ID.eq(requestId)));
    }

    private List<String> statuses(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.STATUS)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetch(record -> record.value1()));
    }

    private int attempts(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.ATTEMPTS)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetchAny(record -> record.value1()));
    }

    private void markSucceeded(String requestId) {
        scoped(() -> dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, DeliveryStatus.SUCCEEDED.name())
                .set(EMAIL_DELIVERIES.COMPLETED_AT, Instant.now())
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .execute());
    }

    private void markDead(String requestId) {
        scoped(() -> dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, DeliveryStatus.DEAD.name())
                .set(EMAIL_DELIVERIES.COMPLETED_AT, Instant.now())
                .set(EMAIL_DELIVERIES.ATTEMPTS, 4)
                .set(EMAIL_DELIVERIES.LAST_ERROR, "rejected")
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .execute());
    }

    /** Runs a query inside the tenant's scope and transaction, because the application role owns nothing else. */
    private <T> T scoped(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(work));
    }
}
