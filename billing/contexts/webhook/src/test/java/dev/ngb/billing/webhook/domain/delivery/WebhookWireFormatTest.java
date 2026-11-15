package dev.ngb.billing.webhook.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.webhook.adapter.delivery.crypto.SharedPayloadSigner;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.webhook.WebhookHeaderSet;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire format every Tenant System verifies. The values below are fixed on purpose: a refactor that changes a
 * header name, the signature prefix or a single signed byte breaks this test instead of breaking a Tenant's
 * receiver in production. The expected signature was computed independently of this code, from the documented rule
 * {@code v1=hex(HMAC-SHA256(secret, timestamp + "." + rawBody))}.
 */
class WebhookWireFormatTest {

    private static final String SECRET = "whsec_test_golden_secret";
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final byte[] BODY =
            "{\"id\":\"0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10\",\"type\":\"charge.succeeded\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void aFixedKeyTimestampAndBodyProduceAFixedSignature() {
        String signature = new SharedPayloadSigner().sign(SECRET, TIMESTAMP, BODY);

        assertThat(signature).isEqualTo("v1=d74d6a31a2e176ab3744f4865f6cf7c70ff5d01140cc5669da15c9a47bcabb47");
    }

    @Test
    void theSignatureCoversTheTimestampBeforeTheDot() {
        String other = new SharedPayloadSigner().sign(SECRET, TIMESTAMP + 1, BODY);

        assertThat(other).isNotEqualTo(new SharedPayloadSigner().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void theSecretIsUsedWholeIncludingItsPrefix() {
        String withoutPrefix = new SharedPayloadSigner().sign("test_golden_secret", TIMESTAMP, BODY);

        assertThat(withoutPrefix).isNotEqualTo(new SharedPayloadSigner().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void theSixHeaderNamesAreTheDocumentedOnes() {
        assertThat(WebhookHeaderSet.BILLING.eventId()).isEqualTo("Billing-Event-Id");
        assertThat(WebhookHeaderSet.BILLING.eventType()).isEqualTo("Billing-Event-Type");
        assertThat(WebhookHeaderSet.BILLING.timestamp()).isEqualTo("Billing-Timestamp");
        assertThat(WebhookHeaderSet.BILLING.keyId()).isEqualTo("Billing-Key-Id");
        assertThat(WebhookHeaderSet.BILLING.signature()).isEqualTo("Billing-Signature");
        assertThat(WebhookHeaderSet.BILLING.attempt()).isEqualTo("Billing-Attempt");
    }

    /** The header constants and {@code HttpHeaders} are one source, so a rename cannot touch only one of them. */
    @Test
    void theHeaderNamesComeFromTheSharedConstants() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(WebhookHeaderSet.BILLING.eventId(), HttpHeaders.BILLING_EVENT_ID);
        expected.put(WebhookHeaderSet.BILLING.eventType(), HttpHeaders.BILLING_EVENT_TYPE);
        expected.put(WebhookHeaderSet.BILLING.timestamp(), HttpHeaders.BILLING_TIMESTAMP);
        expected.put(WebhookHeaderSet.BILLING.keyId(), HttpHeaders.BILLING_KEY_ID);
        expected.put(WebhookHeaderSet.BILLING.signature(), HttpHeaders.BILLING_SIGNATURE);
        expected.put(WebhookHeaderSet.BILLING.attempt(), HttpHeaders.BILLING_ATTEMPT);

        expected.forEach((fromWebhookHeaders, fromHttpHeaders) -> assertThat(fromWebhookHeaders).isEqualTo(fromHttpHeaders));
    }
}
