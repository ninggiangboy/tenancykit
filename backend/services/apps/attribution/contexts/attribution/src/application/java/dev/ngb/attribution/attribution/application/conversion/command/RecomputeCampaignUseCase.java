package dev.ngb.attribution.attribution.application.conversion.command;

import dev.ngb.attribution.attribution.application.conversion.port.ConversionJobs;
import dev.ngb.attribution.attribution.application.conversion.port.ConversionRepository;
import dev.ngb.attribution.attribution.application.conversion.service.ConversionAttributor;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes a campaign's conversions after its model or window changed.
 *
 * <p>It reads each conversion's snapshotted chain, never the raw events, so a recompute reproduces a result even after
 * the partition the chain came from was dropped. A cursor makes the run resumable, and credits are replaced atomically
 * per conversion, so an interrupted run leaves no conversion with two models' credits.
 */
public class RecomputeCampaignUseCase {

    public static final int PAGE_SIZE = 200;

    /** What a recompute pass produced: how far it reached and how many conversions it re-credited. */
    public record Result(UUID cursor, int recomputed, boolean done) {}

    private final UnitOfWork unitOfWork;
    private final ConversionRepository conversions;
    private final ConversionJobs jobs;
    private final ConversionAttributor attributor;

    public RecomputeCampaignUseCase(
            UnitOfWork unitOfWork,
            ConversionRepository conversions,
            ConversionJobs jobs,
            ConversionAttributor attributor) {
        this.unitOfWork = unitOfWork;
        this.conversions = conversions;
        this.jobs = jobs;
        this.attributor = attributor;
    }

    /**
     * One page of a campaign's recompute.
     *
     * @param afterId the cursor a previous pass returned, or null to start
     */
    public Result execute(UUID campaignId, UUID afterId) {
        // The page read runs in the tenant's transaction, because row-level security binds the query to it.
        List<Conversion> page = unitOfWork.transactional(() -> jobs.ownedByCampaign(campaignId, afterId, PAGE_SIZE));
        if (page.isEmpty()) {
            return new Result(afterId, 0, true);
        }
        int recomputed = 0;
        for (Conversion conversion : page) {
            ConversionAttributor.Outcome outcome = unitOfWork.transactional(() -> attributor.recompute(conversion));
            unitOfWork.transactional(() -> conversions.replaceCredits(
                    outcome.conversion(), outcome.model(), outcome.credits()));
            recomputed++;
        }
        UUID lastId = page.getLast().id();
        return new Result(lastId, recomputed, page.size() < PAGE_SIZE);
    }
}
