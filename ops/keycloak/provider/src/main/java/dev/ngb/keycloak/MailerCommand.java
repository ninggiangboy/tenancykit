package dev.ngb.keycloak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.keycloak.util.JsonSerialization;

/**
 * One {@code email.send} command in the suite's envelope.
 *
 * <p>The contract is {@code contracts/messaging/README.md} for the envelope and
 * {@code contracts/commands/email.send.schema.json} for {@code data}; the mailer owns the sender address (the
 * {@code fromKey} is a configuration key, never an address), the transport and the delivery record, so this class
 * only says who gets which already-rendered message. A contract test checks the output against that schema.
 */
final class MailerCommand {

    /** Same value as the suite's other producers; a reader rejects any other envelope version. */
    static final int SCHEMA_VERSION = 2;

    static final String TYPE = "email.send";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UUID eventId;
    private final UUID tenantId;
    private final byte[] body;

    private MailerCommand(UUID eventId, UUID tenantId, byte[] body) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.body = body;
    }

    /**
     * @param tenantId the tenant the recipient belongs to; the mailer keeps its ledger per tenant
     * @param to the one recipient
     * @param text the plain-text body, or {@code null}
     * @param html the HTML body, or {@code null}; at least one of the two must carry content
     */
    static MailerCommand of(
            UUID tenantId, String to, String fromKey, String subject, String text, String html, Instant now) {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId.toString());
        data.put("to", List.of(to));
        data.put("fromKey", fromKey);
        // The schema refuses a line break in the subject, because it is the header-injection vector.
        data.put("subject", subject.replaceAll("[\\r\\n]+", " ").trim());
        if (html != null && !html.isBlank()) {
            data.put("html", html);
        }
        if (text != null && !text.isBlank()) {
            data.put("text", text);
        }

        UUID eventId = uuidV7(now);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", TYPE);
        envelope.put("aggregate", "keycloak/" + requestId);
        envelope.put("tenantId", tenantId.toString());
        envelope.put("occurredAt", now.toString());
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("data", data);
        try {
            return new MailerCommand(
                    eventId, tenantId, JsonSerialization.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("a map of strings could not be written as JSON", e);
        }
    }

    UUID eventId() {
        return eventId;
    }

    UUID tenantId() {
        return tenantId;
    }

    /** The envelope as UTF-8 JSON, the AMQP body. */
    byte[] body() {
        return body.clone();
    }

    /** The envelope id is a UUID v7 the producer chooses, and it is the consumer's deduplication key. */
    static UUID uuidV7(Instant now) {
        long millis = now.toEpochMilli();
        long mostSignificant = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
