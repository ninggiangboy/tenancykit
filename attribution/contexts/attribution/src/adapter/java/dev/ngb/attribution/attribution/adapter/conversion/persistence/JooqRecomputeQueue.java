package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import dev.ngb.attribution.attribution.application.conversion.port.RecomputeQueue;

import static dev.ngb.attribution_jooq.Tables.RECOMPUTE_RUNS;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The recompute queue, backed by {@code recompute_runs}.
 *
 * <p>A run is claimed by flipping it to {@code RUNNING} and stamping {@code started_at}; a run left {@code RUNNING} for
 * longer than a lease is resumed by the next pass, so an interrupted worker does not strand it. The partial unique index
 * on {@code (tenant, campaign)} where the status is pending or running is what stops two runs of one campaign.
 */
public class JooqRecomputeQueue implements RecomputeQueue {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public JooqRecomputeQueue(
            UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Run next() {
        return unitOfWork.transactional(() -> {
            var stale = clock.now().minus(LEASE);
            var row = dsl.selectFrom(RECOMPUTE_RUNS)
                    .where(RECOMPUTE_RUNS.STATUS.in("PENDING", "RUNNING"))
                    .and(RECOMPUTE_RUNS.STATUS.eq("PENDING").or(RECOMPUTE_RUNS.STARTED_AT.le(stale)))
                    .orderBy(RECOMPUTE_RUNS.STARTED_AT.asc())
                    .limit(1)
                    .forUpdate()
                    .skipLocked()
                    .fetchOne();
            if (row == null) {
                return null;
            }
            dsl.update(RECOMPUTE_RUNS)
                    .set(RECOMPUTE_RUNS.STATUS, "RUNNING")
                    .set(RECOMPUTE_RUNS.STARTED_AT, clock.now())
                    .where(RECOMPUTE_RUNS.ID.eq(row.getId()))
                    .execute();
            return new Run(row.getCampaignId(), row.getCursor());
        });
    }

    @Override
    public void advance(Run run, UUID cursor) {
        unitOfWork.transactional(() -> dsl.update(RECOMPUTE_RUNS)
                .set(RECOMPUTE_RUNS.CURSOR, cursor)
                .set(RECOMPUTE_RUNS.STARTED_AT, clock.now())
                .where(RECOMPUTE_RUNS.CAMPAIGN_ID.eq(run.campaignId()))
                .and(RECOMPUTE_RUNS.STATUS.eq("RUNNING"))
                .execute());
    }

    @Override
    public void finish(Run run) {
        unitOfWork.transactional(() -> dsl.update(RECOMPUTE_RUNS)
                .set(RECOMPUTE_RUNS.STATUS, "DONE")
                .set(RECOMPUTE_RUNS.FINISHED_AT, clock.now())
                .where(RECOMPUTE_RUNS.CAMPAIGN_ID.eq(run.campaignId()))
                .and(RECOMPUTE_RUNS.STATUS.in("PENDING", "RUNNING"))
                .execute());
    }

    @Override
    public void fail(Run run, RuntimeException failure) {
        unitOfWork.transactional(() -> dsl.update(RECOMPUTE_RUNS)
                .set(RECOMPUTE_RUNS.STATUS, "PENDING")
                .set(RECOMPUTE_RUNS.LAST_ERROR, failure.getMessage())
                .where(RECOMPUTE_RUNS.CAMPAIGN_ID.eq(run.campaignId()))
                .and(RECOMPUTE_RUNS.STATUS.eq("RUNNING"))
                .execute());
    }

    @Override
    public void enqueue(UUID tenantId, UUID campaignId, String reason) {
        unitOfWork.transactional(() -> {
            boolean alreadyQueued = dsl.fetchExists(
                    dsl.selectOne()
                            .from(RECOMPUTE_RUNS)
                            .where(RECOMPUTE_RUNS.TENANT_ID.eq(tenantId))
                            .and(RECOMPUTE_RUNS.CAMPAIGN_ID.eq(campaignId))
                            .and(RECOMPUTE_RUNS.STATUS.in("PENDING", "RUNNING")));
            if (alreadyQueued) {
                return null;
            }
            dsl.insertInto(RECOMPUTE_RUNS)
                    .set(RECOMPUTE_RUNS.ID, ids.newId())
                    .set(RECOMPUTE_RUNS.TENANT_ID, tenantId)
                    .set(RECOMPUTE_RUNS.CAMPAIGN_ID, campaignId)
                    .set(RECOMPUTE_RUNS.REASON, reason)
                    .set(RECOMPUTE_RUNS.STATUS, "PENDING")
                    .set(RECOMPUTE_RUNS.STARTED_AT, clock.now())
                    .execute();
            return null;
        });
    }

    /** Enqueue for the tenant bound to the current request, which is what a campaign edit is running inside. */
    public void enqueueCurrentTenant(UUID campaignId, String reason) {
        enqueue(tenantContext.requireTenant().value(), campaignId, reason);
    }
}
