package dev.ngb.attribution.links.application.settings.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The write side of a tenant's settings: one upsert of its row. */
@FunctionalInterface
public interface SettingsWriter {

    /**
     * Create or update a tenant's settings row.
     *
     * @param clickParam the parameter the redirect appends, or null to leave it at the deployment default
     * @param allowedOrigins the origins allowed to collect, or null to leave the stored list alone
     * @param defaultWindowDays the campaign window a tenant inherits, or null to leave it alone
     */
    void save(UUID tenantId, String clickParam, List<String> allowedOrigins, Integer defaultWindowDays, Instant now);
}
