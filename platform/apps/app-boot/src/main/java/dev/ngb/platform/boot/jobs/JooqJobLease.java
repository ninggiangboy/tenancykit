package dev.ngb.platform.boot.jobs;

import static dev.ngb.platform_jooq.Tables.JOB_LEASES;

import dev.ngb.core.runtime.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * One named job's lease across every instance, using the Platform's {@code job_leases} table: the row is taken when it
 * is free or its holder stopped renewing, so two instances never run the same job at once.
 */
public class JooqJobLease implements JobLease {

    private final DSLContext dsl;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();

    public JooqJobLease(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String job, Duration duration) {
        Instant now = clock.now();
        Instant expiresAt = now.plus(duration);
        int rows = dsl.insertInto(JOB_LEASES)
                .set(JOB_LEASES.NAME, job)
                .set(JOB_LEASES.OWNER, owner)
                .set(JOB_LEASES.ACQUIRED_AT, now)
                .set(JOB_LEASES.EXPIRES_AT, expiresAt)
                .onConflict(JOB_LEASES.NAME)
                .doUpdate()
                .set(JOB_LEASES.OWNER, owner)
                .set(JOB_LEASES.ACQUIRED_AT, now)
                .set(JOB_LEASES.EXPIRES_AT, expiresAt)
                .where(JOB_LEASES.EXPIRES_AT.lt(now))
                .execute();
        return rows > 0;
    }
}
