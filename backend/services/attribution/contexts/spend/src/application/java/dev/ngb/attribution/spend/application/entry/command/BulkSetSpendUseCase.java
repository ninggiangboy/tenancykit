package dev.ngb.attribution.spend.application.entry.command;

import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.spend.application.entry.port.SpendRepository;
import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sets manual cost for up to 366 days of one campaign in one transaction. */
public class BulkSetSpendUseCase {

    public static final int MAX_DAYS = 366;

    public record DayAmount(LocalDate day, BigDecimal amount, String currency, Long impressions, Long clicks) {}

    public record Command(UUID campaignId, List<DayAmount> days) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SpendRepository costs;
    private final CampaignPolicyReader campaigns;
    private final IdGenerator ids;
    private final Clock clock;

    public BulkSetSpendUseCase(
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

    public List<SpendEntry> execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        List<DayAmount> days = command.days() == null ? List.of() : command.days();
        if (days.isEmpty()) {
            throw new BadRequestException("A bulk cost request needs at least one day.");
        }
        if (days.size() > MAX_DAYS) {
            throw new BadRequestException("A bulk cost request sets at most " + MAX_DAYS + " days.");
        }
        return unitOfWork.transactional(() -> {
            CampaignPolicyReader.Policy policy = campaigns.policy(command.campaignId())
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            List<SpendEntry> entries = new ArrayList<>(days.size());
            for (DayAmount day : days) {
                if (day.day() == null) {
                    throw new BadRequestException("Every cost row needs a day.");
                }
                if (day.amount() == null) {
                    throw new BadRequestException("Every cost row needs an amount.");
                }
                entries.add(SpendEntry.manual(
                        ids.newId(), tenant, command.campaignId(), day.day(), day.amount(),
                        SetManualSpendUseCase.currency(day.currency(), policy.currency()),
                        day.impressions(), day.clicks(), clock.now()));
            }
            costs.upsertAll(entries);
            return entries;
        });
    }
}
