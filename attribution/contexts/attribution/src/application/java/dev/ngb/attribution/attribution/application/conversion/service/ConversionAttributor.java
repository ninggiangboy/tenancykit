package dev.ngb.attribution.attribution.application.conversion.service;

import dev.ngb.attribution.attribution.domain.credit.CreditEngine;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.tracking.api.chain.TouchpointReader;
import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a conversion's touch chain and computes its credit under the owning campaign's model.
 *
 * <p>This is the pure decision shared by the settle job and the recompute job: given a pending conversion it reads the
 * chain (or reuses the one already snapshotted), finds the owning campaign from the last click, and runs the engine. It
 * holds no transaction and writes nothing, so a caller decides when to persist.
 *
 * <p>The chain read spans at most 90 days — the longest a campaign's window can be — so a conversion never reads more
 * history than any policy could use, and a chain with no visitor id is the conversion's own click alone.
 */
public class ConversionAttributor {

    /** The longest window any campaign may set; a chain never needs to look further back than this. */
    private static final int MAX_WINDOW_DAYS = 90;

    private final Clock clock;
    private final TouchpointReader touchpoints;
    private final CampaignPolicyReader policies;

    public ConversionAttributor(Clock clock, TouchpointReader touchpoints, CampaignPolicyReader policies) {
        this.clock = clock;
        this.touchpoints = touchpoints;
        this.policies = policies;
    }

    /**
     * Settle one pending conversion.
     *
     * @return the same conversion with its chain and status, and the credits to store beside it
     */
    public Outcome settle(Conversion conversion) {
        List<CreditEngine.Touch> chain = conversion.snapshot().isEmpty()
                ? readChain(conversion)
                : conversion.snapshot();
        return credit(conversion, chain);
    }

    /**
     * Recompute an already-settled conversion from the chain it snapshot, never from the raw events.
     *
     * <p>This is what a model change triggers: the snapshot is the conversion's own history, so a recompute reproduces a
     * result even after the raw partition it came from was dropped.
     */
    public Outcome recompute(Conversion conversion) {
        return credit(conversion, conversion.snapshot());
    }

    private Outcome credit(Conversion conversion, List<CreditEngine.Touch> chain) {
        if (chain.isEmpty()) {
            CreditEngine.Result empty = CreditEngine.credit(
                    List.of(), CreditEngine.Model.LAST, 1, conversion.value(), conversion.currency(),
                    conversion.occurredAt());
            return new Outcome(
                    conversion.settle(List.of(), empty, clock.now()), CreditEngine.Model.LAST, List.of());
        }
        UUID owningCampaign = owningCampaign(chain);
        Optional<CampaignPolicyReader.Policy> policy = policies.policy(owningCampaign);
        CreditEngine.Model model = policy.map(value -> CreditEngine.Model.valueOf(value.model()))
                .orElse(CreditEngine.Model.LAST);
        int windowDays = policy.map(CampaignPolicyReader.Policy::windowDays).orElse(30);
        CreditEngine.Result result = CreditEngine.credit(
                chain, model, windowDays, conversion.value(), conversion.currency(), conversion.occurredAt());
        return new Outcome(conversion.settle(chain, result, clock.now()), model, result.credits());
    }

    private List<CreditEngine.Touch> readChain(Conversion conversion) {
        Instant since = conversion.occurredAt().minus(MAX_WINDOW_DAYS, ChronoUnit.DAYS);
        List<TouchpointReader.Touchpoint> touchpointsRead = touchpoints.chain(
                conversion.clickId(), conversion.visitorId(), since, conversion.occurredAt());
        List<CreditEngine.Touch> chain = new ArrayList<>(touchpointsRead.size());
        for (TouchpointReader.Touchpoint touch : touchpointsRead) {
            chain.add(new CreditEngine.Touch(
                    touch.clickId().toString(),
                    touch.linkId().toString(),
                    touch.campaignId() == null ? null : touch.campaignId().toString(),
                    touch.at(),
                    touch.human()));
        }
        return chain;
    }

    /** The campaign of the last click in the chain, which governs the conversion. */
    private static UUID owningCampaign(List<CreditEngine.Touch> chain) {
        for (int index = chain.size() - 1; index >= 0; index--) {
            String campaign = chain.get(index).campaignId();
            if (campaign != null) {
                return UUID.fromString(campaign);
            }
        }
        return null;
    }

    /**
     * What settling produced: the conversion to store, the model its credits were computed under (recorded per credit
     * so a report can tell a linear conversion's fractional credit from a first-click one) and the credits themselves.
     */
    public record Outcome(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits) {
        public Outcome {
            credits = List.copyOf(credits);
        }
    }
}
