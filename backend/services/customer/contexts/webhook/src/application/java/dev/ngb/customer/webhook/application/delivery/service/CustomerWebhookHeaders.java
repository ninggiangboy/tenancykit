package dev.ngb.customer.webhook.application.delivery.service;

import dev.ngb.core.webhook.WebhookHeaderSet;

/**
 * The header set of a customer delivery: the six ideas every suite webhook carries, under customer's own {@code Customer-*}
 * prefix, so a receiver tells a customer delivery from another app's by the prefix alone.
 *
 * <p>It lives in the app rather than in {@code core-webhook} because a header set is the producer's wire format: the
 * core keeps the signing rule, and each app names the headers it sends.
 */
public final class CustomerWebhookHeaders {

    public static final WebhookHeaderSet CUSTOMER = new WebhookHeaderSet(
            "Customer-Event-Id", "Customer-Event-Type", "Customer-Timestamp", "Customer-Key-Id", "Customer-Signature", "Customer-Attempt");

    private CustomerWebhookHeaders() {}
}
