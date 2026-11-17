package dev.ngb.customer.boot.export;

import static dev.ngb.customer_jooq.Tables.JOB_LEASES;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.customers.application.customer.exception.ExportInProgressException;
import dev.ngb.customer.customers.application.customer.query.ExportLease;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * One export per tenant, held as a lease in the coordination table every instance reads.
 *
 * <p>The claim is one statement: it inserts the row, or takes it over only when the previous holder's lease has run
 * out. A second request therefore finds the row still in force and is refused, and a process that died mid-export
 * stops blocking its tenant once the lease expires. The claim is committed before the stream starts and released in
 * its own transaction afterwards, so two callers never wait on each other's long read.
 */
public class TenantExportLease implements ExportLease {

    /** What one tenant's export slot is called in the coordination table. */
    private static final String PREFIX = "customer-export:";

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final Duration lease;

    public TenantExportLease(
            DSLContext dsl, UnitOfWork unitOfWork, TenantContext tenantContext, Clock clock, Duration lease) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.lease = lease;
    }

    @Override
    public Hold acquire() {
        String name = PREFIX + tenantContext.requireTenant().value();
        String owner = UUID.randomUUID().toString();
        Instant now = clock.now();
        Instant until = now.plus(lease);
        int claimed = unitOfWork.transactional(() -> dsl.insertInto(JOB_LEASES)
                .set(JOB_LEASES.NAME, name)
                .set(JOB_LEASES.OWNER, owner)
                .set(JOB_LEASES.ACQUIRED_AT, now)
                .set(JOB_LEASES.EXPIRES_AT, until)
                .onConflict(JOB_LEASES.NAME)
                .doUpdate()
                .set(JOB_LEASES.OWNER, owner)
                .set(JOB_LEASES.ACQUIRED_AT, now)
                .set(JOB_LEASES.EXPIRES_AT, until)
                .where(JOB_LEASES.EXPIRES_AT.le(now))
                .execute());
        if (claimed != 1) {
            throw new ExportInProgressException(
                    "Another export of this tenant is still running; wait for it to finish.");
        }
        return () -> unitOfWork.transactional(() -> dsl.deleteFrom(JOB_LEASES)
                .where(JOB_LEASES.NAME.eq(name))
                .and(JOB_LEASES.OWNER.eq(owner))
                .execute());
    }
}
