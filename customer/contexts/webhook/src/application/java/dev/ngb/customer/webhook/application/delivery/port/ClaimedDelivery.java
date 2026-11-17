package dev.ngb.customer.webhook.application.delivery.port;

import dev.ngb.customer.webhook.domain.delivery.WebhookDelivery;

/** A delivery a worker now holds, with what it needs to send it. {@code delivery.attempts()} already counts this attempt. */
public record ClaimedDelivery(WebhookDelivery delivery, String url, String keyId, String secretCiphertext) {}
