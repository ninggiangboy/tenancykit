package dev.ngb.crm.webhook.application.delivery.service;

import dev.ngb.core.webhook.WebhookHeaderSet;

/**
 * The header set of a crm delivery: the six ideas every suite webhook carries, under crm's own {@code Crm-*}
 * prefix, so a receiver tells a crm delivery from another app's by the prefix alone.
 *
 * <p>It lives in the app rather than in {@code core-webhook} because a header set is the producer's wire format: the
 * core keeps the signing rule, and each app names the headers it sends.
 */
public final class CrmWebhookHeaders {

    public static final WebhookHeaderSet CRM = new WebhookHeaderSet(
            "Crm-Event-Id", "Crm-Event-Type", "Crm-Timestamp", "Crm-Key-Id", "Crm-Signature", "Crm-Attempt");

    private CrmWebhookHeaders() {}
}
