package dev.ngb.survey.collection.application.link.port;

import dev.ngb.survey.collection.domain.link.SurveyLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's links: creating a public or personal link and revoking one. */
public interface LinkRepository {

    void insert(SurveyLink link);

    /** Inserts a batch in one statement, which is what a bulk personal import does. */
    void insertAll(List<SurveyLink> links);

    Optional<SurveyLink> findById(UUID linkId);

    /** Whether a public link already exists for this source, which is what makes the source a report's identity. */
    boolean publicSourceTaken(UUID surveyId, String source);

    /** Moves an active link to revoked; {@code false} when the row was already revoked or absent. */
    boolean revoke(UUID linkId, UUID tenantId);
}
