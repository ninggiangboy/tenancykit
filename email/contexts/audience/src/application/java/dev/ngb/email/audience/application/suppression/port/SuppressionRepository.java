package dev.ngb.email.audience.application.suppression.port;

import dev.ngb.email.audience.domain.suppression.Suppression;
import java.util.Optional;

/** The write side of suppressions; the key is the tenant-scoped normalized address. */
public interface SuppressionRepository {

    Optional<Suppression> find(String normalizedAddress);

    /** Inserts or upgrades: an ALL suppression is never lowered to MARKETING by a later unsubscribe. */
    void save(Suppression suppression);

    boolean delete(String normalizedAddress);
}
