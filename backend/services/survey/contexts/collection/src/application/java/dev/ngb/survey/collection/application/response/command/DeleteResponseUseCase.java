package dev.ngb.survey.collection.application.response.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.response.port.ResponseRepository;
import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.port.UploadStore;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import java.util.List;
import java.util.UUID;

/**
 * Deletes a response and everything that belongs to it, because {@code collection} owns the rows.
 *
 * <p>The visit survives with its source, country and device facts: it is the record that a person arrived and left, and
 * it carries no answer, address or token. The counter is not decremented, because it counts submissions rather than
 * rows — releasing a place would let a Tenant inflate its own cap by deleting.
 *
 * <p>The upload objects go with the rows: an explicit delete is the one retention promise the app makes, so nothing of
 * a deleted response outlives it in storage.
 */
public class DeleteResponseUseCase {

    private final UnitOfWork unitOfWork;
    private final ResponseRepository responses;
    private final UploadRepository uploads;
    private final UploadStore store;
    private final VisitRepository visits;

    public DeleteResponseUseCase(
            UnitOfWork unitOfWork,
            ResponseRepository responses,
            UploadRepository uploads,
            UploadStore store,
            VisitRepository visits) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
        this.uploads = uploads;
        this.store = store;
        this.visits = visits;
    }

    public void execute(UUID responseId) {
        List<Upload> objects = unitOfWork.transactional(() -> {
            UUID visitId = responses.visitIdOf(responseId)
                    .orElseThrow(() -> new NotFoundException("No response exists here."));
            List<Upload> visitUploads = uploads.ofVisit(visitId);
            responses.erase(responseId);
            visits.detachResponse(visitId);
            return visitUploads;
        });
        // The objects are deleted outside the transaction: an S3 call cannot roll back, so a failed one leaves a row the
        // housekeeping sweep will find rather than a row claiming an object still exists.
        objects.forEach(upload -> store.delete(upload.objectKey()));
    }
}
