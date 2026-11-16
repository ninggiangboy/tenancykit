package dev.ngb.survey.collection.application.response.port;

import dev.ngb.survey.collection.domain.response.Response;
import java.util.Optional;
import java.util.UUID;

/** The write side of responses: the in-progress draft a visit holds, and the completion that finalises it. */
public interface ResponseRepository {

    void insert(Response response);

    /** The response of one visit, which is the browser's own identity for its draft. */
    Optional<Response> findByVisitId(UUID visitId);

    /** Replaces the in-progress row, guarded by its status so a completed response is never rewritten. */
    boolean saveDraft(Response response);

    /**
     * Increments the survey's completed counter when the cap allows it, creating the row on the first submit.
     *
     * @param cap the version's response cap, or null when it states none; the caller knows it because a version is
     *     authoring's data, not this context's
     * @return false when the cap is reached, which the caller answers as a conflict
     */
    boolean countCompletion(UUID surveyId, UUID tenantId, Long cap);

    /**
     * Completes a response and inserts its typed answers in one statement each.
     *
     * @return false when the row is no longer in progress, or when a personal link already completed
     */
    boolean complete(Response response, java.util.List<TypedAnswer> answers);

    /** Whether a response already completed under this idempotency key, which makes a retry return that result. */
    Optional<UUID> completedByIdempotencyKey(String idempotencyKey);

    /** The visit a response belongs to, which a deletion needs for the uploads it must remove. */
    Optional<UUID> visitIdOf(UUID responseId);

    /**
     * Hard-deletes a response and its typed answers.
     *
     * <p>The answers cascade from the response row, so this is one statement; the upload objects are a storage
     * concern the caller handles outside the transaction.
     */
    void erase(UUID responseId);
}
