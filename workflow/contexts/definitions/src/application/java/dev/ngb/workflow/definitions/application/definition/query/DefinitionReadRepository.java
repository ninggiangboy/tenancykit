package dev.ngb.workflow.definitions.application.definition.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's definitions and their published versions. */
public interface DefinitionReadRepository {

    Page<DefinitionView> list(ListQuery query);

    Optional<DefinitionView> findById(UUID id);

    Optional<VersionView> findVersion(UUID definitionId, int number);

    /** Every published version of one definition, newest first. */
    List<VersionView> versions(UUID definitionId);
}
