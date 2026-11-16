package dev.ngb.survey.webhook.adapter.delivery.crypto;

import dev.ngb.survey.webhook.application.delivery.port.PayloadSigner;
import dev.ngb.core.webhook.WebhookSigner;

/**
 * Billing's signing adapter: the rule lives in {@code core-webhook}, shared with every app that delivers a webhook, so
 * two apps cannot sign the same bytes differently. This class is the port's only implementation and holds no rule.
 */
public final class SharedPayloadSigner implements PayloadSigner {

    private final WebhookSigner signer = new WebhookSigner();

    @Override
    public String sign(String secret, long timestampSeconds, byte[] body) {
        return signer.sign(secret, timestampSeconds, body);
    }
}
