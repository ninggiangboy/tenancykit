package dev.ngb.survey.collection.application.response.query;

import java.util.Optional;
import java.util.UUID;

/** The read side of responses; row-level security narrows every query to the bound tenant. */
public interface ResponseReadRepository {

    /** The response of one visit, which is what a browser resumes and a submit reads. */
    Optional<ResponseView> findByVisitId(UUID visitId);

    Optional<ResponseView> findById(UUID responseId);
}
