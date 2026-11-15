package dev.ngb.email.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.api.audience.AudienceDirectory;
import dev.ngb.email.content.api.render.RenderTemplate;
import dev.ngb.email.sending.adapter.campaign.persistence.JooqCampaignReadRepository;
import dev.ngb.email.sending.adapter.campaign.persistence.JooqCampaignRecipientRepository;
import dev.ngb.email.sending.adapter.campaign.persistence.JooqCampaignRepository;
import dev.ngb.email.sending.adapter.campaign.persistence.JooqCampaignStatsReadRepository;
import dev.ngb.email.sending.application.campaign.command.AdvanceCampaignsUseCase;
import dev.ngb.email.sending.application.campaign.command.ChangeCampaignStateUseCase;
import dev.ngb.email.sending.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.PublishCampaignBatchUseCase;
import dev.ngb.email.sending.application.campaign.command.ScheduleCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.StartCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.TestCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.email.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.campaign.query.CampaignQueries;
import dev.ngb.email.sending.application.campaign.query.CampaignReadRepository;
import dev.ngb.email.sending.application.campaign.query.CampaignStatsQueries;
import dev.ngb.email.sending.application.campaign.query.CampaignStatsReadRepository;
import dev.ngb.email.sending.application.campaign.service.CampaignSnapshotter;
import dev.ngb.email.sending.application.email.command.SendEmailUseCase;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the campaign aggregate, its snapshot and its publisher. */
@Configuration(proxyBeanMethods = false)
class CampaignConfiguration {

    @Bean
    CampaignRepository campaignRepository(DSLContext dsl) {
        return new JooqCampaignRepository(dsl);
    }

    @Bean
    CampaignRecipientRepository campaignRecipientRepository(DSLContext dsl) {
        return new JooqCampaignRecipientRepository(dsl);
    }

    @Bean
    CampaignReadRepository campaignReadRepository(DSLContext dsl) {
        return new JooqCampaignReadRepository(dsl);
    }

    @Bean
    CampaignQueries campaignQueries(UnitOfWork unitOfWork, CampaignReadRepository campaigns) {
        return new CampaignQueries(unitOfWork, campaigns);
    }

    @Bean
    CampaignStatsReadRepository campaignStatsRepository(DSLContext dsl) {
        return new JooqCampaignStatsReadRepository(dsl);
    }

    @Bean
    CampaignStatsQueries campaignStatsQueries(UnitOfWork unitOfWork, CampaignStatsReadRepository stats) {
        return new CampaignStatsQueries(unitOfWork, stats);
    }

    @Bean
    CampaignSnapshotter campaignSnapshotter(
            AudienceDirectory audience, CampaignRecipientRepository recipients) {
        return new CampaignSnapshotter(audience, recipients);
    }

    @Bean
    CreateCampaignUseCase createCampaignUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            CampaignRepository campaigns,
            RenderTemplate templates,
            EmailSettingsRepository settings,
            IdGenerator ids,
            Clock clock) {
        return new CreateCampaignUseCase(
                unitOfWork, tenantContext, campaigns, templates, settings, ids, clock);
    }

    @Bean
    UpdateCampaignUseCase updateCampaignUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            RenderTemplate templates,
            EmailSettingsRepository settings,
            Clock clock) {
        return new UpdateCampaignUseCase(unitOfWork, campaigns, templates, settings, clock);
    }

    @Bean
    ScheduleCampaignUseCase scheduleCampaignUseCase(
            UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        return new ScheduleCampaignUseCase(unitOfWork, campaigns, clock);
    }

    @Bean
    StartCampaignUseCase startCampaignUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            CampaignSnapshotter snapshotter,
            Clock clock) {
        return new StartCampaignUseCase(unitOfWork, campaigns, snapshotter, clock);
    }

    @Bean
    ChangeCampaignStateUseCase changeCampaignStateUseCase(
            UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        return new ChangeCampaignStateUseCase(unitOfWork, campaigns, clock);
    }

    @Bean
    TestCampaignUseCase testCampaignUseCase(
            UnitOfWork unitOfWork, CampaignRepository campaigns, SendEmailUseCase send) {
        return new TestCampaignUseCase(unitOfWork, campaigns, send);
    }

    @Bean
    AdvanceCampaignsUseCase advanceCampaignsUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            CampaignSnapshotter snapshotter,
            Clock clock) {
        return new AdvanceCampaignsUseCase(unitOfWork, campaigns, snapshotter, clock);
    }

    @Bean
    PublishCampaignBatchUseCase publishCampaignBatchUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            CampaignRepository campaigns,
            CampaignRecipientRepository recipients,
            AudienceDirectory audience,
            SendEmailUseCase send,
            EmailSettingsRepository settings,
            Clock clock,
            EmailSettings emailSettings) {
        return new PublishCampaignBatchUseCase(
                unitOfWork, tenantContext, campaigns, recipients, audience, send, settings, clock,
                emailSettings.campaign().publisherInterval());
    }
}
