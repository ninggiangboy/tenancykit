package dev.ngb.survey.authoring.application.flow.query;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the document a preview or a simulation runs over, whichever of the two a caller names.
 *
 * <p>It is a read port: both queries select only, and row-level security narrows them to the bound tenant, so a
 * survey of another tenant answers exactly as one that does not exist.
 */
public interface FlowDocumentReadRepository {

    /** The current draft document of a survey. */
    Optional<Map<String, Object>> draftDocument(UUID surveyId);

    /** One published version's document. */
    Optional<Map<String, Object>> versionDocument(UUID surveyId, int versionNo);
}
