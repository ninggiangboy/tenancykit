package dev.ngb.attribution.tracking.adapter.ingest.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.TrafficClass;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published payload must match {@code contracts/attribution/ingest/click.schema.json} exactly: the consumer
 * validates against that file, so a field this writer renames is a click silently discarded in production.
 */
class ClickPublisherWireTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private final JsonMapper json = new JsonMapper();
    private final SchemaRegistry registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @Test
    void aFullPayloadValidatesAgainstTheCommittedSchema() throws Exception {
        ClickEvent event = new ClickEvent(
                UUID.randomUUID(), new TenantId(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NOW, DeviceClass.MOBILE, "Safari", "VN", "news.example",
                "qr", TrafficClass.HUMAN, "hash");

        assertThat(validate(wire(event))).as("the click payload must satisfy the ingest schema").isEmpty();
    }

    @Test
    void absentOptionalMembersAreOmittedRatherThanWrittenAsNull() {
        ClickEvent event = new ClickEvent(
                UUID.randomUUID(), new TenantId(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(),
                null, null, NOW, DeviceClass.DESKTOP, "Chrome", null, null, null, TrafficClass.BOT, "hash");

        Map<String, Object> wire = wire(event);

        assertThat(wire).doesNotContainKeys("destination_id", "campaign_id", "country", "referrer_domain", "entry");
        assertThat(wire).containsKeys(
                "event_id", "tenant_id", "click_id", "link_id", "occurred_at", "device_class", "browser_family",
                "traffic_class", "visitor_hash");
    }

    @Test
    void aMinimalPayloadValidatesAgainstTheSchema() throws Exception {
        ClickEvent event = new ClickEvent(
                UUID.randomUUID(), new TenantId(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(),
                null, null, NOW, DeviceClass.UNKNOWN, "Unknown", null, null, null, TrafficClass.BOT, "hash");

        assertThat(validate(wire(event))).isEmpty();
    }

    private Map<String, Object> wire(ClickEvent event) {
        return new ClickPublisher(
                        null, json, "attribution.ingest", new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .wire(event);
    }

    private java.util.List<com.networknt.schema.Error> validate(Map<String, Object> payload) throws IOException {
        try (InputStream schemaFile = Files.newInputStream(
                repositoryRoot().resolve("contracts/attribution/ingest/click.schema.json"))) {
            var schema = registry.getSchema(schemaFile);
            return schema.validate(json.readTree(json.writeValueAsString(payload)));
        }
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
