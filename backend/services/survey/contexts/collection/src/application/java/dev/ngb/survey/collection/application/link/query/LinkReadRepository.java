package dev.ngb.survey.collection.application.link.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's links; row-level security narrows every query to the bound tenant. */
public interface LinkReadRepository {

    Page<LinkView> list(ListQuery query, UUID surveyId);

    Optional<LinkView> find(UUID linkId);
}
