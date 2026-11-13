package dev.ngb.tenant.webhook.api.event;

/**
 * Turns a delivery body into the envelope the policy understands. The adapter behind it parses JSON, so the
 * use case calls it only after the signature over the raw bytes has been verified.
 */
@FunctionalInterface
public interface WebhookBodyParser {

    WebhookEnvelope parse(byte[] rawBody);
}
