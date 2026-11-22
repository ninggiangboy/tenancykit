package dev.ngb.survey.collection.api.response;

import java.util.UUID;

/**
 * The one write {@code insight} asks of {@code collection}: delete a response and everything that belongs to it.
 *
 * <p>A hard delete belongs to the context that owns the rows and the upload objects, so {@code insight} — which writes
 * nothing but its own export ledger — reaches it through this seam rather than deleting anything itself.
 */
public interface ResponseDeleter {

    /**
     * Deletes a response, its typed answers and its upload objects.
     *
     * @throws dev.ngb.core.error.NotFoundException when the tenant has no such response
     */
    void delete(UUID responseId);
}
