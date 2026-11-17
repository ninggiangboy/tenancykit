package dev.ngb.workflow.runtime.api.instance;

import java.util.UUID;

/**
 * One call that starts a workflow.
 *
 * @param versionId the published version to pin
 * @param input the payload the graph reads as {@code input}
 * @param correlationKey the key a wait may match, or null
 * @param startedBy the audit name of who or what started it
 * @param idempotencyKey a client-chosen key, or null when the call is not repeatable
 * @param requestHash a digest of the request, so the same key with another body is a conflict
 */
public record StartRequest(
        UUID versionId,
        Object input,
        String correlationKey,
        String startedBy,
        String idempotencyKey,
        String requestHash) {}
