package dev.ngb.billing.sharedkernel.idempotency;

import java.util.Objects;

/**
 * The identity of the request an aggregate was written for: the idempotency key it was accepted under and the
 * digest of that request, so a repeat is recognised and a repeated key with a different request is a conflict.
 * It keeps the two technical fields of a row in one value, out of the aggregate's own vocabulary.
 */
public record ReplayIdentity(String idempotencyKey, String requestHash) {

    public ReplayIdentity {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
    }
}
