package dev.ngb.crm.customers.application.field.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;

/** Reads field definitions straight into the view; the aggregate is never rebuilt for a read. */
public interface FieldDefinitionReadRepository {

    Page<FieldDefinitionView> list(ListQuery query);

    Optional<FieldDefinitionView> findByKey(String key);
}
