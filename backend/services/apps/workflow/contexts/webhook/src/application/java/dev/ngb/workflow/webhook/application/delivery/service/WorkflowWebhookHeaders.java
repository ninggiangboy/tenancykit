package dev.ngb.workflow.webhook.application.delivery.service;

import dev.ngb.core.webhook.WebhookHeaderSet;

/**
 * The header set of a workflow delivery: the six ideas every suite webhook carries, under Workflow's own {@code Workflow-*}
 * prefix, so a receiver tells a workflow delivery from another app's by the prefix alone.
 *
 * <p>It lives in the app rather than in {@code core-webhook} because a header set is the producer's wire format: the
 * core keeps the signing rule, and each app names the headers it sends.
 */
public final class WorkflowWebhookHeaders {

    public static final WebhookHeaderSet WORKFLOW = new WebhookHeaderSet(
            "Workflow-Event-Id", "Workflow-Event-Type", "Workflow-Timestamp", "Workflow-Key-Id", "Workflow-Signature", "Workflow-Attempt");

    private WorkflowWebhookHeaders() {}
}
