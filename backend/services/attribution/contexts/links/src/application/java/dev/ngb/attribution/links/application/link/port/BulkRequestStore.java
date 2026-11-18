package dev.ngb.attribution.links.application.link.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers the outcome of a bulk request, keyed by the caller's {@code Idempotency-Key}, so a retry returns the
 * batch it first produced and a reused key with a different body is refused instead of minting a second batch.
 */
public interface BulkRequestStore {

    Optional<BulkResult> find(String key);

    /** The request hash recorded with a key, so a replay is told apart from a conflicting reuse. */
    Optional<String> requestHash(String key);

    void save(String key, String requestHash, BulkResult result);

    /** The links a bulk request produced, in request order. */
    record BulkResult(List<PersonalLink> links) {}

    /** One link of a batch: where to follow it and which row it answers. */
    record PersonalLink(UUID id, String slug, String url, String externalRef) {}
}
