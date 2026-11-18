package dev.ngb.booking.catalog.application.offering.port;

import dev.ngb.booking.catalog.domain.offering.Offering;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and locks offerings with their requirements and cancellation thresholds. Every statement is tenant-scoped by
 * row-level security, not by a condition here.
 */
public interface OfferingRepository {

    Optional<Offering> findById(UUID id);

    /** The offering a conditional change writes, held under a row lock so two editors cannot interleave. */
    Optional<Offering> lockById(UUID id);

    boolean existsByCode(String code);

    void insert(Offering offering);

    /** Writes the offering and replaces its requirements and thresholds with the sets it now holds. */
    void update(Offering offering);

    /** Writes only the archived status and the version, leaving the requirements and thresholds as they are. */
    void archive(Offering offering);

    /** @return true when every named resource exists in the tenant and is {@code ACTIVE} */
    boolean resourcesExistAndAreActive(Collection<UUID> resourceIds);

    /** @return true when every named group exists in the tenant */
    boolean groupsExist(Collection<UUID> groupIds);
}
