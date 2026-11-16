package dev.ngb.attribution.links.api.campaign;

import java.util.UUID;

/**
 * One campaign's attribution policy as the attribution context needs it: the model, the window and the currency.
 *
 * <p>The attribution context never reads a campaign's rows directly — the architecture lets it reach links only through
 * this api — so this is the one shape it sees. The model and window here are the ones that govern a conversion, because
 * the campaign of the last click in the chain owns it.
 */
public interface CampaignPolicyReader {

    /**
     * The policy of one campaign.
     *
     * @return the policy, or empty when the campaign does not exist in this tenant
     */
    java.util.Optional<Policy> policy(UUID campaignId);

    /**
     * @param model {@code FIRST}, {@code LAST} or {@code LINEAR}
     * @param windowDays the campaign's window, 1 to 90 days
     * @param currency the campaign's ISO 4217 currency
     */
    record Policy(UUID campaignId, String model, int windowDays, String currency) {}
}
