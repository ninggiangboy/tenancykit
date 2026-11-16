package dev.ngb.attribution.attribution.application.conversion.port;

import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The conversions a job needs: the pending ones to settle and, for a recompute, the ones a campaign owns.
 *
 * <p>It reads through the write port's own shape on purpose — a job decides with the aggregate, not a report view — and
 * it is scoped to the current tenant by row-level security like every other read.
 */
public interface ConversionJobs {

    /**
     * Pending conversions created before an instant, oldest first, claimed with {@code for update skip locked} so two
     * workers do not settle the same row.
     *
     * @param settleBefore a conversion older than this is old enough to settle
     * @param limit the most rows one pass claims
     */
    List<Conversion> claimPending(Instant settleBefore, int limit);

    /**
     * The conversions a campaign owns, by id after a cursor, for a resumable recompute.
     *
     * @param campaignId the owning campaign
     * @param afterId the last id a previous pass processed, or null to start from the beginning
     * @param limit the most rows one page carries
     */
    List<Conversion> ownedByCampaign(UUID campaignId, UUID afterId, int limit);
}
