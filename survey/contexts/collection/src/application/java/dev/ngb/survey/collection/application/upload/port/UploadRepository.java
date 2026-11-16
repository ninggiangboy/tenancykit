package dev.ngb.survey.collection.application.upload.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The upload store: what one visit created, what a submit attaches, and what the sweep deletes. */
public interface UploadRepository {

    void insert(Upload upload);

    Optional<Upload> findById(UUID uploadId);

    /** The uploads one visit created, which is the set a submit may reference. */
    List<Upload> ofVisit(UUID visitId);

    /** Moves a pending upload to attached once its object matched what was declared. */
    void attach(UUID uploadId);

    /** Pending uploads older than this instant, which the housekeeping sweep deletes with their objects. */
    List<Upload> pendingBefore(Instant before, int limit);

    void delete(UUID uploadId);
}
