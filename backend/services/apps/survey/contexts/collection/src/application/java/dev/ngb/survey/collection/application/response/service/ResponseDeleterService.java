package dev.ngb.survey.collection.application.response.service;

import dev.ngb.survey.collection.api.response.ResponseDeleter;
import dev.ngb.survey.collection.application.response.command.DeleteResponseUseCase;
import java.util.UUID;

/**
 * Serves {@link ResponseDeleter} to {@code insight}, because a delete belongs to the context that owns the rows.
 *
 * <p>It is a service rather than a use case: the use case it delegates to is the one that opens the transaction, so a
 * second one here would nest pointlessly.
 */
public class ResponseDeleterService implements ResponseDeleter {

    private final DeleteResponseUseCase delete;

    public ResponseDeleterService(DeleteResponseUseCase delete) {
        this.delete = delete;
    }

    @Override
    public void delete(UUID responseId) {
        delete.execute(responseId);
    }
}
