package dev.ngb.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The signing rule is one place, so Billing's and Survey's deliveries cannot drift apart. */
class WebhookSignerTest {

    private static final String SECRET = "whsec_test_golden_secret";
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final byte[] BODY =
            "{\"id\":\"0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10\",\"type\":\"charge.succeeded\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void aFixedKeyTimestampAndBodyProduceAFixedSignature() {
        assertThat(new WebhookSigner().sign(SECRET, TIMESTAMP, BODY))
                .isEqualTo("v1=d74d6a31a2e176ab3744f4865f6cf7c70ff5d01140cc5669da15c9a47bcabb47");
    }

    @Test
    void theTimestampIsPartOfTheSignedPayload() {
        assertThat(new WebhookSigner().sign(SECRET, TIMESTAMP + 1, BODY))
                .isNotEqualTo(new WebhookSigner().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void theSecretIsUsedWholeIncludingItsPrefix() {
        assertThat(new WebhookSigner().sign("test_golden_secret", TIMESTAMP, BODY))
                .isNotEqualTo(new WebhookSigner().sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    void eachAppHasItsOwnHeaderSetUnderItsOwnPrefix() {
        assertThat(WebhookHeaderSet.BILLING.eventId()).isEqualTo("Billing-Event-Id");
        assertThat(WebhookHeaderSet.SURVEY.eventId()).isEqualTo("Survey-Event-Id");
        assertThat(WebhookHeaderSet.BILLING.signature()).isEqualTo("Billing-Signature");
        assertThat(WebhookHeaderSet.SURVEY.signature()).isEqualTo("Survey-Signature");
        assertThat(WebhookHeaderSet.BILLING).isNotEqualTo(WebhookHeaderSet.SURVEY);
    }

    @Test
    void theSignatureVersionIsTheSameForEverySet() {
        assertThat(WebhookHeaderSet.SIGNATURE_VERSION).isEqualTo("v1");
    }
}
