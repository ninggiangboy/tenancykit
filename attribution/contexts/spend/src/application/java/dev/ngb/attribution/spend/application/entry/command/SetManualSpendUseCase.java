package dev.ngb.attribution.spend.application.entry.command;

import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.spend.application.entry.port.SpendRepository;
import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Sets the manual cost of one campaign and day, refusing a currency the campaign does not use. */
public class SetManualSpendUseCase {

    /** @param currency the requested currency, or null to use the campaign's */
    public record Command(
            UUID campaignId, LocalDate day, BigDecimal amount, String currency, Long impressions, Long clicks) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SpendRepository costs;
    private final CampaignPolicyReader campaigns;
    private final IdGenerator ids;
    private final Clock clock;

    public SetManualSpendUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SpendRepository costs,
            CampaignPolicyReader campaigns,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.costs = costs;
        this.campaigns = campaigns;
        this.ids = ids;
        this.clock = clock;
    }

    public SpendEntry execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        if (command.day() == null) {
            throw new BadRequestException("A cost entry needs a day.");
        }
        if (command.amount() == null) {
            throw new BadRequestException("A cost entry needs an amount.");
        }
        return unitOfWork.transactional(() -> {
            CampaignPolicyReader.Policy policy = campaigns.policy(command.campaignId())
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            String currency = currency(command.currency(), policy.currency());
            SpendEntry entry = SpendEntry.manual(
                    ids.newId(), tenant, command.campaignId(), command.day(), command.amount(), currency,
                    command.impressions(), command.clicks(), clock.now());
            costs.upsert(entry);
            return entry;
        });
    }

    /** The campaign's currency is used unless the caller names one, and a different one is a conflict, not a conversion. */
    static String currency(String requested, String campaignCurrency) {
        if (requested == null || requested.isBlank()) {
            return campaignCurrency;
        }
        String normalised = requested.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalised.equals(campaignCurrency)) {
            throw new ConflictException("currency_mismatch");
        }
        return normalised;
    }
}
