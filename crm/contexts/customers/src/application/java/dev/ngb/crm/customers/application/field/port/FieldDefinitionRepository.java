package dev.ngb.crm.customers.application.field.port;

import dev.ngb.crm.customers.domain.field.FieldDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** How the write side stores field definitions. The Tenant's key is the identity of a definition. */
public interface FieldDefinitionRepository {

    /**
     * Inserts a definition the caller believes does not exist yet.
     *
     * @return false when another caller stored the same key first, which the caller answers with a conflict
     */
    boolean insertIfAbsent(FieldDefinition definition);

    /** Stores the current state of a definition the caller holds a lock on. */
    void update(FieldDefinition definition);

    /** The definition with its row locked until the transaction ends, so two patches of one key apply in turn. */
    Optional<FieldDefinition> lock(String key);

    /** The definitions of these keys that accept values now: the ones a written value is checked against. */
    List<FieldDefinition> active(Collection<String> keys);

    /** The number of definitions of this tenant that accept values now. */
    int countActive();
}
