package dev.ngb.email.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.api.eligibility.SendEligibility;
import dev.ngb.email.content.api.render.RenderTemplate;
import dev.ngb.email.sending.adapter.delivery.messaging.MailerDeliveryResultHandler;
import dev.ngb.email.sending.adapter.email.messaging.ContractEmailCommandFactory;
import dev.ngb.email.sending.adapter.email.messaging.EmailCommandSchemaValidator;
import dev.ngb.email.sending.adapter.email.persistence.JooqEmailEventRepository;
import dev.ngb.email.sending.adapter.email.persistence.JooqEmailReadRepository;
import dev.ngb.email.sending.adapter.email.persistence.JooqEmailRepository;
import dev.ngb.email.sending.adapter.settings.persistence.JooqEmailSettingsRepository;
import dev.ngb.email.sending.application.email.command.ApplyDeliveryResultUseCase;
import dev.ngb.email.sending.application.email.command.SendEmailUseCase;
import dev.ngb.email.sending.application.email.port.EmailCommandFactory;
import dev.ngb.email.sending.application.email.port.EmailEventRepository;
import dev.ngb.email.sending.application.email.port.EmailRepository;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.email.port.TrackingHook;
import dev.ngb.email.sending.application.email.query.EmailQueries;
import dev.ngb.email.sending.application.email.query.EmailReadRepository;
import dev.ngb.email.sending.application.email.service.BatchSender;
import dev.ngb.email.sending.application.housekeeping.command.HousekeepingUseCase;
import dev.ngb.email.sending.application.settings.command.SaveEmailSettingsUseCase;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.email.sending.application.settings.service.SettingsReader;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the sending context: the ledger, the command producer, the result consumer and the settings. */
@Configuration(proxyBeanMethods = false)
class SendingConfiguration {

    @Bean
    EmailSettingsView emailSettingsDefaults(EmailSettings settings) {
        EmailSettings.Defaults defaults = settings.defaults();
        return new EmailSettingsView(
                defaults.fromKey(),
                blankToNull(defaults.fromName()),
                blankToNull(defaults.replyTo()),
                defaults.bodyRetentionDays(),
                defaults.campaignPublishPerMinute(),
                0);
    }

    @Bean
    EmailRepository emailRepository(DSLContext dsl) {
        return new JooqEmailRepository(dsl);
    }

    @Bean
    EmailEventRepository emailEventRepository(DSLContext dsl) {
        return new JooqEmailEventRepository(dsl);
    }

    @Bean
    EmailReadRepository emailReadRepository(DSLContext dsl) {
        return new JooqEmailReadRepository(dsl);
    }

    @Bean
    EmailSettingsRepository emailSettingsRepository(
            DSLContext dsl,
            EmailSettingsView emailSettingsDefaults,
            TenantContext tenantContext,
            Clock clock) {
        return new JooqEmailSettingsRepository(dsl, emailSettingsDefaults, tenantContext, clock);
    }

    @Bean
    EmailCommandSchemaValidator emailCommandSchemaValidator(JsonMapper json) {
        return new EmailCommandSchemaValidator(json);
    }

    @Bean
    EmailCommandFactory emailCommandFactory(EmailCommandSchemaValidator validator) {
        return new ContractEmailCommandFactory(validator);
    }

    @Bean
    EmailQueries emailQueries(UnitOfWork unitOfWork, EmailReadRepository emails) {
        return new EmailQueries(unitOfWork, emails);
    }

    @Bean
    SettingsReader settingsReader(UnitOfWork unitOfWork, EmailSettingsRepository settings) {
        return new SettingsReader(unitOfWork, settings);
    }

    @Bean
    SaveEmailSettingsUseCase saveEmailSettingsUseCase(
            UnitOfWork unitOfWork, EmailSettingsRepository settings) {
        return new SaveEmailSettingsUseCase(unitOfWork, settings);
    }

    @Bean
    SendEmailUseCase sendEmailUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            EmailRepository emails,
            EmailSettingsRepository settings,
            RenderTemplate renderer,
            SendEligibility eligibility,
            EmailCommandFactory commands,
            EventPublisher publisher,
            TrackingHook tracking,
            IdGenerator ids,
            Clock clock) {
        return new SendEmailUseCase(
                unitOfWork, tenantContext, emails, settings, renderer, eligibility, commands, publisher,
                tracking, ids, clock);
    }

    @Bean
    BatchSender batchSender(SendEmailUseCase send) {
        return new BatchSender(send);
    }

    @Bean
    ApplyDeliveryResultUseCase applyDeliveryResultUseCase(
            UnitOfWork unitOfWork, EmailRepository emails, EmailEventRepository events, IdGenerator ids) {
        return new ApplyDeliveryResultUseCase(unitOfWork, emails, events, ids);
    }

    @Bean
    MailerDeliveryResultHandler mailerDeliveryResultHandler(ApplyDeliveryResultUseCase apply) {
        return new MailerDeliveryResultHandler(apply);
    }

    @Bean
    HousekeepingUseCase housekeepingUseCase(
            UnitOfWork unitOfWork,
            EmailSettingsRepository settings,
            EmailRepository emails,
            dev.ngb.email.sending.application.tracking.port.EmailLinkRepository links,
            dev.ngb.email.sending.application.campaign.port.CampaignRecipientRepository recipients,
            Clock clock) {
        return new HousekeepingUseCase(unitOfWork, settings, emails, links, recipients, clock);
    }

    @Bean
    dev.ngb.email.boot.jobs.MessagingHousekeeping messagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, EmailSettings settings, Clock clock) {
        return new dev.ngb.email.boot.jobs.MessagingHousekeeping(
                unitOfWork, dsl, settings.housekeeping(), clock);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
