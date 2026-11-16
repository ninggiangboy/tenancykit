package dev.ngb.survey.collection.application.link.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's links; row-level security narrows every query to the bound tenant. */
public interface LinkReadRepository {

    List<LinkView> list(UUID surveyId);

    Optional<LinkView> find(UUID linkId);
}
