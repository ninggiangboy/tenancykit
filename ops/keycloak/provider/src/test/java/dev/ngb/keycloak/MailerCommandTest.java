package dev.ngb.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The command is checked against the schema the mailer and every other producer share, not against a copy of it. */
class MailerCommandTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("0198c1a0-0000-7000-8000-000000000042");
    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00.123456Z");

    @Test
    void theDataMemberSatisfiesTheSharedEmailSendSchema() throws IOException {
        MailerCommand command = MailerCommand.of(
                TENANT, "an@acme.vn", "noreply", "Reset your password", "plain body", "<p>html body</p>", NOW);

        List<Error> errors = schema().validate(envelope(command).get("data"));

        assertTrue(errors.isEmpty(), () -> "the data member breaks the shared schema: " + errors);
    }

    @Test
    void theEnvelopeCarriesTheTenantTheTypeAndTheSuiteSchemaVersion() throws IOException {
        MailerCommand command = MailerCommand.of(TENANT, "an@acme.vn", "noreply", "Subject", "text", null, NOW);

        JsonNode envelope = envelope(command);

        assertEquals(TENANT.toString(), envelope.get("tenantId").asString());
        assertEquals("email.send", envelope.get("type").asString());
        assertEquals(2, envelope.get("schemaVersion").asInt());
        assertEquals(NOW.toString(), envelope.get("occurredAt").asString());
        assertEquals(command.eventId().toString(), envelope.get("id").asString());
        assertTrue(envelope.get("aggregate").asString().startsWith("keycloak/"));
        assertEquals("an@acme.vn", envelope.get("data").get("to").get(0).asString());
        assertEquals("noreply", envelope.get("data").get("fromKey").asString());
    }

    @Test
    void theEnvelopeIdIsAVersionSevenUuidThatCarriesTheTime() {
        UUID id = MailerCommand.uuidV7(NOW);

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertEquals(NOW.toEpochMilli(), id.getMostSignificantBits() >>> 16);
    }

    @Test
    void everyCommandIsANewRequestSoTwoResetsToTheSameAddressBothSend() throws IOException {
        String first = envelope(MailerCommand.of(TENANT, "an@acme.vn", "noreply", "S", "t", null, NOW))
                .get("data").get("requestId").asString();
        String second = envelope(MailerCommand.of(TENANT, "an@acme.vn", "noreply", "S", "t", null, NOW))
                .get("data").get("requestId").asString();

        assertFalse(first.equals(second));
    }

    @Test
    void aLineBreakInTheSubjectIsNotAllowedToReachTheHeader() throws IOException {
        MailerCommand command = MailerCommand.of(
                TENANT, "an@acme.vn", "noreply", "Reset\r\nBcc: someone@evil.test", "text", null, NOW);

        JsonNode data = envelope(command).get("data");

        assertEquals("Reset Bcc: someone@evil.test", data.get("subject").asString());
        assertTrue(schema().validate(data).isEmpty());
    }

    @Test
    void aBlankBodyIsLeftOutSoTheSchemaStillSeesOneBody() throws IOException {
        JsonNode data = envelope(MailerCommand.of(TENANT, "an@acme.vn", "noreply", "S", " ", "<p>x</p>", NOW)).get("data");

        assertFalse(data.has("text"));
        assertEquals("<p>x</p>", data.get("html").asString());
    }

    private static JsonNode envelope(MailerCommand command) {
        return JSON.readTree(new String(command.body(), StandardCharsets.UTF_8));
    }

    private static Schema schema() throws IOException {
        Path path = Path.of(System.getProperty("email.send.schema"));
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.readTree(Files.readString(path)));
    }
}
