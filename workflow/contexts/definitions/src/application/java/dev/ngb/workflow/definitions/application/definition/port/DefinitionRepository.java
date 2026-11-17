package dev.ngb.workflow.definitions.application.definition.port;

import dev.ngb.workflow.definitions.domain.definition.Definition;
import java.util.Optional;
import java.util.UUID;

/** The write side of a definition: load it, change it, store it under its optimistic version. */
public interface DefinitionRepository {

    void add(Definition definition);

    /** Writes the row when the stored version is older than the loaded one, and fails otherwise. */
    void update(Definition definition);

    Optional<Definition> findById(UUID id);

    /** Locks the row for the change that follows, so two editors cannot both pass their {@code If-Match}. */
    Optional<Definition> lockById(UUID id);

    Optional<Definition> findByCode(String code);
}
