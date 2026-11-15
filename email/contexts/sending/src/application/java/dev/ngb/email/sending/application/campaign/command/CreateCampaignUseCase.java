package dev.ngb.email.sending.application.campaign.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.content.api.render.RenderTemplate;
import dev.ngb.email.content.api.render.TemplateSummary;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.campaign.CampaignTargetType;
import dev.ngb.email.shared.TemplateKey;
import java.util.UUID;

/** Creates a DRAFT campaign and pins the template revision it will send. */
public class CreateCampaignUseCase {

    public record Command(
            String name, String templateKey, CampaignTargetType targetType, UUID targetId, String fromKey) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final CampaignRepository campaigns;
    private final RenderTemplate templates;
    private final EmailSettingsRepository settings;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateCampaignUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            CampaignRepository campaigns,
            RenderTemplate templates,
            EmailSettingsRepository settings,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.campaigns = campaigns;
        this.templates = templates;
        this.settings = settings;
        this.ids = ids;
        this.clock = clock;
    }

    public Campaign execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            TemplateSummary template = templates.resolve(new TemplateKey(command.templateKey()));
            EmailSettingsView effective = settings.current();
            String fromKey = command.fromKey() == null || command.fromKey().isBlank()
                    ? effective.fromKey()
                    : command.fromKey().trim();
            Campaign campaign = Campaign.create(
                    ids.newId(), tenantId, command.name(), template.id(), template.revision(),
                    command.targetType(), command.targetId(), fromKey, effective.fromName(), effective.replyTo(),
                    clock.now());
            campaigns.insert(campaign);
            return campaign;
        });
    }
}
