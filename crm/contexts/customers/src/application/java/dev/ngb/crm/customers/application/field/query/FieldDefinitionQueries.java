package dev.ngb.crm.customers.application.field.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.customers.application.field.service.FieldRules;

/** Reads the current tenant's field definitions for the GET handler. */
public class FieldDefinitionQueries {

    private final UnitOfWork unitOfWork;
    private final FieldDefinitionReadRepository definitions;

    public FieldDefinitionQueries(UnitOfWork unitOfWork, FieldDefinitionReadRepository definitions) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
    }

    public Page<FieldDefinitionView> list(ListQuery query) {
        return unitOfWork.transactional(() -> definitions.list(query));
    }

    /** @throws dev.ngb.crm.customers.application.field.exception.InvalidFieldException when the key is not usable */
    public FieldDefinitionView get(String key) {
        String normalised = FieldRules.key(key);
        return unitOfWork.transactional(() -> definitions
                .findByKey(normalised)
                .orElseThrow(dev.ngb.crm.customers.application.field.exception.CustomFieldNotFoundException::new));
    }
}
