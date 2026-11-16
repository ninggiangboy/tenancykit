package dev.ngb.booking.catalog.application.group.port;

import dev.ngb.booking.catalog.domain.group.ResourceGroup;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Stores and locks resource groups. Every statement is tenant-scoped by row-level security, not by a condition here. */
public interface GroupRepository {

    Optional<ResourceGroup> findById(UUID id);

    /** The group a conditional change writes, held under a row lock so two editors cannot interleave. */
    Optional<ResourceGroup> lockById(UUID id);

    boolean existsByCode(String code);

    void insert(ResourceGroup group);

    void update(ResourceGroup group);

    /** @return true when every named resource exists in the tenant and is {@code ACTIVE} */
    boolean resourcesExistAndAreActive(Collection<UUID> resourceIds);
}
