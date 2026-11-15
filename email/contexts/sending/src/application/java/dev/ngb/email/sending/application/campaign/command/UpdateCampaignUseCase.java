package dev.ngb.email.sending.application.campaign.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.content.api.render.RenderTemplate;
import dev.ngb.email.content.api.render.TemplateSummary;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.campaign.CampaignTargetType;
import dev.ngb.email.shared.TemplateKey;
import java.util.UUID;

/** Edits a DRAFT campaign, re-pinning the template revision it will send. */
public class UpdateCampaignUseCase {

    public record Change(
            String name, String templateKey, CampaignTargetType targetType, UUID targetId, String fromKey) {}

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final RenderTemplate templates;
    private final EmailSettingsRepository settings;
    private final Clock clock;

    public UpdateCampaignUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            RenderTemplate templates,
            EmailSettingsRepository settings,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.templates = templates;
        this.settings = settings;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            TemplateSummary template = templates.resolve(new TemplateKey(change.templateKey()));
            EmailSettingsView effective = settings.current();
            String fromKey = change.fromKey() == null || change.fromKey().isBlank()
                    ? effective.fromKey()
                    : change.fromKey().trim();
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            expected.check(current.version());
            Campaign updated = current.withDetails(
                    change.name(), template.id(), template.revision(), change.targetType(), change.targetId(),
                    fromKey, effective.fromName(), effective.replyTo(), clock.now());
            if (!campaigns.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
