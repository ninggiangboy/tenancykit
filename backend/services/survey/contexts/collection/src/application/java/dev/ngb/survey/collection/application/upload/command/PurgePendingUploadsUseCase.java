package dev.ngb.survey.collection.application.upload.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.port.UploadStore;
import java.time.Duration;
import java.util.List;

/**
 * Deletes pending uploads that never became part of a submit, and their objects.
 *
 * <p>A respondent who starts a file upload and then closes the tab leaves an object nobody references. Rather than
 * keeping it forever, the sweep removes a pending upload older than the configured retention: the row goes in one
 * transaction and the object follows, so a storage failure leaves a row the next sweep finds.
 */
public class PurgePendingUploadsUseCase {

    private final UnitOfWork unitOfWork;
    private final UploadRepository uploads;
    private final UploadStore store;
    private final Clock clock;

    public PurgePendingUploadsUseCase(
            UnitOfWork unitOfWork, UploadRepository uploads, UploadStore store, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.uploads = uploads;
        this.store = store;
        this.clock = clock;
    }

    /**
     * @param retention how old a pending upload must be before it is deleted
     * @param limit the most rows one sweep removes, so a large backlog is several bounded runs
     * @return how many uploads were deleted
     */
    public int execute(Duration retention, int limit) {
        List<Upload> orphans = unitOfWork.transactional(() -> {
            List<Upload> pending = uploads.pendingBefore(clock.now().minus(retention), limit);
            pending.forEach(upload -> uploads.delete(upload.id()));
            return pending;
        });
        orphans.forEach(upload -> store.delete(upload.objectKey()));
        return orphans.size();
    }
}
