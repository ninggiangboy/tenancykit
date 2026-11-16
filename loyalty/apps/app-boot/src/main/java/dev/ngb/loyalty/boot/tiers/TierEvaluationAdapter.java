package dev.ngb.loyalty.boot.tiers;

import dev.ngb.loyalty.points.application.earn.port.TierEvaluator;
import dev.ngb.loyalty.points.application.member.query.TierProgressReadRepository;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvaluationService;
import java.util.UUID;

/**
 * Answers the points context's two questions about a member's tier with the evaluation that owns the answer.
 *
 * <p>It is the seam the context graph needs: the points context declares what it asks for after an earn and what it shows
 * as the next tier's distance, and this adapter hands both to the evaluation, so neither context imports the other's
 * internals and the graph stays acyclic. It holds no state and decides nothing itself.
 */
public class TierEvaluationAdapter implements TierEvaluator, TierProgressReadRepository {

    private final TierEvaluationService evaluation;

    public TierEvaluationAdapter(TierEvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @Override
    public void afterEarn(UUID memberId) {
        evaluation.afterEarn(memberId);
    }

    @Override
    public void afterReturn(UUID memberId) {
        evaluation.afterReturn(memberId);
    }

    @Override
    public long remainingSpend(UUID memberId, long minSpend) {
        return evaluation.remainingSpend(memberId, minSpend);
    }
}
