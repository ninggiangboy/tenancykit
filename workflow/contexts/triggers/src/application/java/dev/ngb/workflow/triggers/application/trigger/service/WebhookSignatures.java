package dev.ngb.workflow.triggers.application.trigger.service;

import dev.ngb.core.error.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How a signed hook is verified: {@code v1=} plus the lowercase hex HMAC-SHA256 of {@code timestamp + "." + rawBody},
 * keyed with the trigger's secret, exactly the suite's signing rule.
 *
 * <p>Each producer names its headers under its own prefix, and the verifier knows each set, so a delivery from another
 * app cannot be replayed at this engine by renaming a header. A timestamp outside the window, a missing header and a
 * wrong digest all answer the same refusal.
 */
public final class WebhookSignatures {

    /** The version prefix of the signature header value. */
    public static final String SIGNATURE_VERSION = "v1";

    /** How far from now a delivery's timestamp may be. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    /** The headers one source signs with. */
    public record Headers(String eventId, String eventType, String timestamp, String keyId, String signature) {}

    private static final Map<String, Headers> SOURCES = Map.of(
            "billing", new Headers("Billing-Event-Id", "Billing-Event-Type", "Billing-Timestamp", "Billing-Key-Id", "Billing-Signature"),
            "survey", new Headers("Survey-Event-Id", "Survey-Event-Type", "Survey-Timestamp", "Survey-Key-Id", "Survey-Signature"),
            "customer", new Headers("Customer-Event-Id", "Customer-Event-Type", "Customer-Timestamp", "Customer-Key-Id", "Customer-Signature"),
            "loyalty", new Headers("Loyalty-Event-Id", "Loyalty-Event-Type", "Loyalty-Timestamp", "Loyalty-Key-Id", "Loyalty-Signature"),
            "mailroom", new Headers("Mailroom-Event-Id", "Mailroom-Event-Type", "Mailroom-Timestamp", "Mailroom-Key-Id", "Mailroom-Signature"),
            "external", new Headers("X-Workflow-Event-Id", "X-Workflow-Event-Type", "X-Workflow-Timestamp", "X-Workflow-Key-Id", "X-Workflow-Signature"));

    private WebhookSignatures() {}

    /** The headers a known source signs with; an unknown source signs with the external set. */
    public static Headers forSource(String source) {
        return SOURCES.getOrDefault(source, SOURCES.get("external"));
    }

    /** Whether a suite app code has a known header set, which is what an APP trigger may name. */
    public static boolean knownSource(String source) {
        return SOURCES.containsKey(source) && !"external".equals(source);
    }

    /**
     * Verifies one delivery. A header map is matched case-insensitively, as HTTP is.
     *
     * @throws UnauthorizedException when the timestamp, a header or the digest does not hold; the message says nothing
     *     about which, so a prober learns nothing
     */
    public static Delivery verified(
            String source, Map<String, String> headers, String rawBody, String secret, Instant now) {
        Headers names = forSource(source);
        String timestamp = header(headers, names.timestamp());
        String signature = header(headers, names.signature());
        if (timestamp == null || signature == null || rawBody == null) {
            throw refused();
        }
        long epoch;
        try {
            epoch = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw refused();
        }
        if (Duration.between(Instant.ofEpochSecond(epoch), now).abs().compareTo(WINDOW) > 0) {
            throw refused();
        }
        String expected = SIGNATURE_VERSION + "=" + hmacHex(secret, epoch + "." + rawBody);
        if (!dev.ngb.workflow.shared.secret.SecretBox.sameSecret(expected, signature)) {
            throw refused();
        }
        return new Delivery(
                header(headers, names.eventId()), header(headers, names.eventType()), source);
    }

    /**
     * What the signature proved.
     *
     * @param eventId the producer's id, or null when the body must carry it
     * @param eventType the producer's type, or null when the body must carry it
     */
    public record Delivery(String eventId, String eventType, String source) {}

    private static UnauthorizedException refused() {
        return new UnauthorizedException("The delivery could not be verified.");
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JVM", e);
        }
    }
}
