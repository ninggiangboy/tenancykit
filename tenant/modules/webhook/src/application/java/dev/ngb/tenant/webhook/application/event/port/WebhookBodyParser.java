package dev.ngb.tenant.webhook.application.event.port;

import dev.ngb.tenant.webhook.application.event.service.WebhookEnvelope;

/**
 * Turns a delivery body into the envelope the policy understands. The adapter behind it parses JSON, so the
 * use case calls it only after the signature over the raw bytes has been verified.
 */
@FunctionalInterface
public interface WebhookBodyParser {

    WebhookEnvelope parse(byte[] rawBody);
}
