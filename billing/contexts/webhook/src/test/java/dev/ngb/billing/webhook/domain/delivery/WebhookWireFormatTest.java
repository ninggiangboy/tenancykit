package dev.ngb.billing.webhook.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.webhook.adapter.delivery.crypto.HmacSha256Signer;
import dev.ngb.core.http.HttpHeaders;
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
        String signature = new HmacSha256Signer().sign(SECRET, TIMESTAMP, BODY);

        assertThat(signature).isEqualTo("v1=d74d6a31a2e176ab3744f4865f6cf7c70ff5d01140cc5669da15c9a47bcabb47");
    }

    @Test
    void theSignatureCoversTheTimestampBeforeTheDot() {
        String other = new HmacSha256Signer().sign(SECRET, TIMESTAMP + 1, BODY);

        assertThat(other).isNotEqualTo(new HmacSha256Signer().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void theSecretIsUsedWholeIncludingItsPrefix() {
        String withoutPrefix = new HmacSha256Signer().sign("test_golden_secret", TIMESTAMP, BODY);

        assertThat(withoutPrefix).isNotEqualTo(new HmacSha256Signer().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void theSixHeaderNamesAreTheDocumentedOnes() {
        assertThat(WebhookHeaders.EVENT_ID).isEqualTo("Billing-Event-Id");
        assertThat(WebhookHeaders.EVENT_TYPE).isEqualTo("Billing-Event-Type");
        assertThat(WebhookHeaders.TIMESTAMP).isEqualTo("Billing-Timestamp");
        assertThat(WebhookHeaders.KEY_ID).isEqualTo("Billing-Key-Id");
        assertThat(WebhookHeaders.SIGNATURE).isEqualTo("Billing-Signature");
        assertThat(WebhookHeaders.ATTEMPT).isEqualTo("Billing-Attempt");
    }

    /** The header constants and {@code HttpHeaders} are one source, so a rename cannot touch only one of them. */
    @Test
    void theHeaderNamesComeFromTheSharedConstants() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(WebhookHeaders.EVENT_ID, HttpHeaders.BILLING_EVENT_ID);
        expected.put(WebhookHeaders.EVENT_TYPE, HttpHeaders.BILLING_EVENT_TYPE);
        expected.put(WebhookHeaders.TIMESTAMP, HttpHeaders.BILLING_TIMESTAMP);
        expected.put(WebhookHeaders.KEY_ID, HttpHeaders.BILLING_KEY_ID);
        expected.put(WebhookHeaders.SIGNATURE, HttpHeaders.BILLING_SIGNATURE);
        expected.put(WebhookHeaders.ATTEMPT, HttpHeaders.BILLING_ATTEMPT);

        expected.forEach((fromWebhookHeaders, fromHttpHeaders) -> assertThat(fromWebhookHeaders).isEqualTo(fromHttpHeaders));
    }
}
