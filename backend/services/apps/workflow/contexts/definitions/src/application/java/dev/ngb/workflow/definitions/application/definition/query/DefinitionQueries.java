package dev.ngb.workflow.definitions.application.definition.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's definitions for the GET handlers. */
public class DefinitionQueries {

    private final UnitOfWork unitOfWork;
    private final DefinitionReadRepository definitions;

    public DefinitionQueries(UnitOfWork unitOfWork, DefinitionReadRepository definitions) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
    }

    public Page<DefinitionView> list(ListQuery query) {
        return unitOfWork.transactional(() -> definitions.list(query));
    }

    public DefinitionView get(UUID id) {
        return unitOfWork.transactional(() -> definitions
                .findById(id)
                .orElseThrow(() -> new NotFoundException("No workflow definition exists here.")));
    }

    public VersionView version(UUID definitionId, int number) {
        return unitOfWork.transactional(() -> definitions
                .findVersion(definitionId, number)
                .orElseThrow(() -> new NotFoundException("No such version of this workflow exists here.")));
    }

    public List<VersionView> versions(UUID definitionId) {
        return unitOfWork.transactional(() -> definitions.versions(definitionId));
    }
}
