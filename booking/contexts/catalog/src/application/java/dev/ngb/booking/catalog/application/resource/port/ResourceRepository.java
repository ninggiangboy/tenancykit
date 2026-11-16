package dev.ngb.booking.catalog.application.resource.port;

import dev.ngb.booking.catalog.domain.resource.Resource;
import java.util.Optional;
import java.util.UUID;

/** How the write side stores resources. Every statement is narrowed to the current tenant by row-level security. */
public interface ResourceRepository {

    Optional<Resource> findById(UUID id);

    /** The resource with its row locked until the transaction ends, so two callers change it in turn. */
    Optional<Resource> lockById(UUID id);

    /** Whether the Tenant already chose this code for another resource, which a create refuses. */
    boolean existsByCode(String code);

    void insert(Resource resource);

    /** Stores a change to the resource's name, seats and status, guarded by the version the caller holds. */
    void update(Resource resource);

    /** Stores the archived state of a resource the caller decided to keep. */
    void archive(Resource resource);

    /**
     * Deletes the resource when nothing references it.
     *
     * @return false when an offering's requirements or a group's membership names it, which is what makes a referenced
     *     resource archived rather than deleted
     */
    boolean deleteIfUnreferenced(UUID id);

    /** Whether an offering's requirements or a group's membership names this resource. */
    boolean isReferenced(UUID id);
}
