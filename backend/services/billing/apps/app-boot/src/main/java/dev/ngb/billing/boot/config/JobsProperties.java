package dev.ngb.billing.boot.config;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The switch and tuning of the background jobs a worker runs.
 *
 * @param enabled whether the scheduled jobs are registered at all
 * @param tenantParallelism how many tenants one sweep runs at a time; 1 runs them one after another
 * @param expireReservationsBatch how many expired reservations one round releases
 * @param verifyLedgerNotBefore the UTC time of day before which the ledger verification does not run
 */
@ConfigurationProperties(prefix = "billing.jobs")
public record JobsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1") int tenantParallelism,
        @DefaultValue("200") int expireReservationsBatch,
        @DefaultValue("03:00") LocalTime verifyLedgerNotBefore) {}
