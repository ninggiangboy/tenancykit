package dev.ngb.workflow.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.domain.event.RuntimeEvents;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.tasks.domain.task.Task;
import dev.ngb.workflow.tasks.domain.task.TaskStatus;
import dev.ngb.workflow.tasks.domain.task.event.TaskEvents;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every fact workflow announces is validated against the schema it is committed under. The emitters are the same ones the
 * engine and the tasks context publish with, so a payload and its contract cannot drift apart.
 */
class WorkflowEventContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void instanceStartedMatchesItsSchema() {
        assertHolds(RuntimeEvents.started(instance(InstanceStatus.RUNNING), "dunning", UUID.randomUUID(), NOW));
    }

    @Test
    void instanceCompletedMatchesItsSchema() {
        Instance instance = instance(InstanceStatus.RUNNING).finished(InstanceStatus.COMPLETED, NOW);
        assertHolds(RuntimeEvents.finished(
                instance, "dunning", InstanceStatus.COMPLETED, UUID.randomUUID(), NOW));
    }

    @Test
    void instanceCancelledMatchesItsSchema() {
        Instance instance = instance(InstanceStatus.RUNNING).finished(InstanceStatus.CANCELLED, NOW);
        assertHolds(RuntimeEvents.finished(
                instance, "dunning", InstanceStatus.CANCELLED, UUID.randomUUID(), NOW));
    }

    @Test
    void instanceFailedMatchesItsSchema() {
        assertHolds(RuntimeEvents.failed(
                instance(InstanceStatus.RUNNING), "dunning", "FATAL", "http_call_failed:400", UUID.randomUUID(), NOW));
    }

    @Test
    void instanceStuckMatchesItsSchema() {
        assertHolds(RuntimeEvents.stuck(
                instance(InstanceStatus.RUNNING), "dunning", "http_call_failed:400", UUID.randomUUID(), NOW));
    }

    @Test
    void stepDeadLetteredMatchesItsSchema() {
        assertHolds(RuntimeEvents.stepDeadLettered(
                instance(InstanceStatus.RUNNING),
                "dunning",
                "pay",
                "FATAL",
                "http_call_failed:400",
                UUID.randomUUID(),
                NOW));
    }

    @Test
    void taskCreatedMatchesItsSchema() {
        assertHolds(TaskEvents.created(task(TaskStatus.OPEN), UUID.randomUUID(), NOW));
    }

    @Test
    void taskCompletedMatchesItsSchema() {
        Task completed = task(TaskStatus.OPEN).completed("approve", Map.of("note", "ok"), "user/1", "USER", NOW);
        assertHolds(TaskEvents.completed(completed, UUID.randomUUID(), NOW));
    }

    @Test
    void taskEscalatedMatchesItsSchema() {
        Task escalated = task(TaskStatus.OPEN).escalated(null, NOW);
        assertHolds(TaskEvents.escalated(escalated, UUID.randomUUID(), NOW));
    }

    @Test
    void taskExpiredMatchesItsSchema() {
        assertHolds(TaskEvents.expired(task(TaskStatus.OPEN).expired(NOW), UUID.randomUUID(), NOW));
    }

    private static void assertHolds(DomainEvent event) {
        JsonNode data = JSON.readTree(JSON.writeValueAsString(event.payload()));
        Schema schema = schema(event.type());
        assertThat(schema.validate(data))
                .as(event.type() + " matches its committed schema; payload was " + data)
                .isEmpty();
        assertThat(event.aggregate()).isNotBlank();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.tenantId()).isEqualTo(TENANT);
    }

    private static Schema schema(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = WorkflowEventContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath; the build packages it");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Instance instance(InstanceStatus status) {
        Instance running = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of(), null, "test", null,
                null, NOW);
        return status == InstanceStatus.RUNNING ? running : running.finished(status, NOW);
    }

    private static Task task(TaskStatus status) {
        UUID group = UUID.randomUUID();
        return new Task(
                UUID.randomUUID(),
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "approve",
                "Approve the refund",
                null,
                null,
                group,
                status,
                List.of("approve", "reject"),
                NOW.plusSeconds(3600),
                group,
                null,
                NOW.plusSeconds(7200),
                null,
                null,
                null,
                null,
                null,
                null,
                NOW,
                NOW,
                1);
    }
}
