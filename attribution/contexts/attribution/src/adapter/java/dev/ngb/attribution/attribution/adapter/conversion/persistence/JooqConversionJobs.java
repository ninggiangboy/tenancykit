package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;

import dev.ngb.attribution.attribution.application.conversion.port.ConversionJobs;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The job queries over conversions: the pending ones to settle, and the ones a campaign owns for a recompute.
 *
 * <p>A pending batch is claimed with {@code for update skip locked}, so two workers running the same pass do not settle
 * the same conversion twice; each conversion is then written in its own transaction by the caller. A campaign's
 * conversions are paged by id, which is what makes a recompute resumable from a cursor.
 */
public class JooqConversionJobs implements ConversionJobs {

    private final DSLContext dsl;
    private final JooqConversionRepository repository;

    public JooqConversionJobs(DSLContext dsl, JooqConversionRepository repository) {
        this.dsl = dsl;
        this.repository = repository;
    }

    @Override
    public List<Conversion> claimPending(Instant settleBefore, int limit) {
        return dsl.selectFrom(CONVERSIONS)
                .where(CONVERSIONS.CREDIT_STATUS.eq("PENDING"))
                .and(CONVERSIONS.CREATED_AT.le(settleBefore))
                .orderBy(CONVERSIONS.CREATED_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(repository::toConversionPublic);
    }

    @Override
    public List<Conversion> ownedByCampaign(UUID campaignId, UUID afterId, int limit) {
        var where = CONVERSIONS.OWNING_CAMPAIGN_ID.eq(campaignId);
        if (afterId != null) {
            where = where.and(CONVERSIONS.ID.gt(afterId));
        }
        return dsl.selectFrom(CONVERSIONS)
                .where(where)
                .orderBy(CONVERSIONS.ID.asc())
                .limit(limit)
                .fetch(repository::toConversionPublic);
    }
}
