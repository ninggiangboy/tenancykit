package dev.ngb.notification.boot.config;

import dev.ngb.notification.boot.replica.Transition;
import dev.ngb.notification.boot.replica.TransitionSink;
import dev.ngb.notification.dispatch.adapter.notification.email.ContractEmailCommandFactory;
import dev.ngb.notification.dispatch.adapter.notification.email.EmailCommandSchemaValidator;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqEmailDispatchRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqHousekeepingRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationPreferenceRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationSettingsRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqTemplateSource;
import dev.ngb.notification.dispatch.adapter.notification.render.ClasspathDefaultTemplates;
import dev.ngb.notification.dispatch.adapter.notification.render.JmustacheTemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.command.AcceptChangeUseCase;
import dev.ngb.notification.dispatch.application.notification.command.EmailDispatchUseCase;
import dev.ngb.notification.dispatch.application.notification.command.HousekeepingUseCase;
import dev.ngb.notification.dispatch.application.notification.port.DefaultTemplates;
import dev.ngb.notification.dispatch.application.notification.port.EmailCommandFactory;
import dev.ngb.notification.dispatch.application.notification.port.EmailDispatchRepository;
import dev.ngb.notification.dispatch.application.notification.port.HousekeepingRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationPreferenceRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationSettingsRepository;
import dev.ngb.notification.dispatch.application.notification.port.TemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.port.TemplateSource;
import dev.ngb.notification.dispatch.application.notification.service.AudienceResolver;
import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.HousekeepingSettings;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessChangedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessDisabledRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessGrantedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.EntitlementChangedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.NotificationCatalog;
import dev.ngb.notification.dispatch.application.notification.service.catalog.TenantDeactivatedRule;
import dev.ngb.notification.platform.api.replica.TenantDirectory;
import dev.ngb.notification.platform.api.replica.UserDirectory;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the dispatch context: the catalog, renderer, in-app store, email publisher and housekeeping. */
@Configuration(proxyBeanMethods = false)
class DispatchConfiguration {

    @Bean
    NotificationCatalog notificationCatalog() {
        // Order matters only for documentation today: the truth table's transitions are disjoint, and the catalog
        // refuses a transition two rules both claim instead of picking one.
        return new NotificationCatalog(List.of(
                new AccessGrantedRule(),
                new AccessDisabledRule(),
                new AccessChangedRule(),
                new EntitlementChangedRule(),
                new TenantDeactivatedRule()));
    }

    @Bean
    AudienceResolver audienceResolver(UserDirectory users) {
        return new AudienceResolver(users);
    }

    @Bean
    TemplateSource templateSource(DSLContext dsl) {
        return new JooqTemplateSource(dsl);
    }

    @Bean
    DefaultTemplates defaultTemplates(NotificationSettings settings) {
        return new ClasspathDefaultTemplates(settings.templates().maxPartBytes());
    }

    @Bean
    TemplateRenderer templateRenderer(
            TemplateSource overrides, DefaultTemplates defaults, NotificationSettings settings) {
        return new JmustacheTemplateRenderer(
                overrides, defaults, settings.templates().maxPartBytes());
    }

    @Bean
    NotificationRepository notificationRepository(DSLContext dsl) {
        return new JooqNotificationRepository(dsl);
    }

    @Bean
    NotificationSettingsRepository notificationSettingsRepository(DSLContext dsl) {
        return new JooqNotificationSettingsRepository(dsl);
    }

    @Bean
    NotificationPreferenceRepository notificationPreferenceRepository(DSLContext dsl) {
        return new JooqNotificationPreferenceRepository(dsl);
    }

    @Bean
    EmailDispatchRepository emailDispatchRepository(DSLContext dsl) {
        return new JooqEmailDispatchRepository(dsl);
    }

    @Bean
    HousekeepingRepository housekeepingRepository(DSLContext dsl) {
        return new JooqHousekeepingRepository(dsl);
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
    HousekeepingSettings housekeepingSettings(NotificationSettings settings) {
        NotificationSettings.Housekeeping housekeeping = settings.housekeeping();
        return new HousekeepingSettings(
                housekeeping.enabled(),
                housekeeping.notificationRetention(),
                housekeeping.dispatchRetention(),
                housekeeping.inboxRetention(),
                housekeeping.deadOutboxRetention(),
                housekeeping.batchSize());
    }

    @Bean
    HousekeepingUseCase housekeepingUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            HousekeepingRepository repository,
            HousekeepingSettings settings,
            dev.ngb.core.runtime.Clock clock) {
        return new HousekeepingUseCase(unitOfWork, repository, settings, clock);
    }

    @Bean
    EmailDispatchUseCase emailDispatchUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            EmailDispatchRepository dispatches,
            EmailCommandFactory commands,
            dev.ngb.core.event.EventPublisher publisher,
            dev.ngb.core.runtime.Clock clock,
            dev.ngb.core.runtime.IdGenerator ids,
            NotificationSettings settings) {
        return new EmailDispatchUseCase(
                unitOfWork, dispatches, commands, publisher, clock, ids, settings.email().fromKey());
    }

    @Bean
    AcceptChangeUseCase acceptChangeUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            TenantDirectory tenants,
            NotificationCatalog catalog,
            AudienceResolver audience,
            TemplateRenderer renderer,
            NotificationSettingsRepository settings,
            NotificationPreferenceRepository preferences,
            NotificationRepository notifications,
            EmailDispatchUseCase emailDispatch,
            dev.ngb.core.runtime.Clock clock,
            dev.ngb.core.runtime.IdGenerator ids) {
        return new AcceptChangeUseCase(
                unitOfWork, tenants, catalog, audience, renderer, settings, preferences, notifications,
                emailDispatch, clock, ids);
    }

    /** The one production transition sink: every applied replica event is offered to the catalog. */
    @Bean
    TransitionSink acceptTransitions(AcceptChangeUseCase useCase) {
        return (Transition transition) -> useCase.accept(new Change(
                transition.eventId(),
                transition.eventType(),
                transition.tenantId(),
                transition.previousUser(),
                transition.currentUser(),
                transition.previousTenant(),
                transition.currentTenant(),
                transition.appCode(),
                transition.previousEntitlement(),
                transition.currentEntitlement()));
    }

    /** Only the API runtime writes templates through the use case; the bean exists in both. */
    @Bean
    dev.ngb.notification.dispatch.application.notification.port.TemplateOverrideRepository templateOverrides(DSLContext dsl) {
        return new dev.ngb.notification.dispatch.adapter.notification.persistence.JooqTemplateOverrides(dsl);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.query.NotificationReadRepository notificationReadRepository(
            DSLContext dsl) {
        return new dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationReadRepository(dsl);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.query.DispatchReadRepository dispatchReadRepository(
            DSLContext dsl) {
        return new dev.ngb.notification.dispatch.adapter.notification.persistence.JooqDispatchReadRepository(dsl);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.port.NotificationReadStateRepository notificationReadStateRepository(
            DSLContext dsl) {
        return new dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationReadStateRepository(dsl);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.query.ListNotificationsQuery listNotificationsQuery(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            dev.ngb.notification.dispatch.application.notification.query.NotificationReadRepository repository,
            dev.ngb.core.tenancy.TenantContext tenantContext,
            dev.ngb.core.security.CurrentActor currentActor) {
        return new dev.ngb.notification.dispatch.application.notification.query.ListNotificationsQuery(
                unitOfWork, repository, tenantContext, currentActor);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.query.ListDispatchesQuery listDispatchesQuery(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            dev.ngb.notification.dispatch.application.notification.query.DispatchReadRepository repository,
            dev.ngb.core.tenancy.TenantContext tenantContext) {
        return new dev.ngb.notification.dispatch.application.notification.query.ListDispatchesQuery(
                unitOfWork, repository, tenantContext);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.MarkNotificationReadUseCase markNotificationReadUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            dev.ngb.notification.dispatch.application.notification.port.NotificationReadStateRepository repository,
            dev.ngb.core.tenancy.TenantContext tenantContext,
            dev.ngb.core.security.CurrentActor currentActor,
            dev.ngb.core.runtime.Clock clock) {
        return new dev.ngb.notification.dispatch.application.notification.command.MarkNotificationReadUseCase(
                unitOfWork, repository, tenantContext, currentActor, clock);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.GetPreferenceUseCase getPreferenceUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            NotificationPreferenceRepository preferences,
            dev.ngb.core.tenancy.TenantContext tenantContext,
            dev.ngb.core.security.CurrentActor currentActor) {
        return new dev.ngb.notification.dispatch.application.notification.command.GetPreferenceUseCase(
                unitOfWork, preferences, tenantContext, currentActor);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.SavePreferenceUseCase savePreferenceUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            NotificationPreferenceRepository preferences,
            dev.ngb.core.tenancy.TenantContext tenantContext,
            dev.ngb.core.security.CurrentActor currentActor) {
        return new dev.ngb.notification.dispatch.application.notification.command.SavePreferenceUseCase(
                unitOfWork, preferences, tenantContext, currentActor);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.GetTenantSettingsUseCase getTenantSettingsUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            NotificationSettingsRepository settings,
            dev.ngb.core.tenancy.TenantContext tenantContext) {
        return new dev.ngb.notification.dispatch.application.notification.command.GetTenantSettingsUseCase(
                unitOfWork, settings, tenantContext);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.SaveTenantSettingsUseCase saveTenantSettingsUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            NotificationSettingsRepository settings,
            dev.ngb.core.tenancy.TenantContext tenantContext) {
        return new dev.ngb.notification.dispatch.application.notification.command.SaveTenantSettingsUseCase(
                unitOfWork, settings, tenantContext);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.ResolveTemplateUseCase resolveTemplateUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            TemplateSource overrides,
            DefaultTemplates defaults) {
        return new dev.ngb.notification.dispatch.application.notification.command.ResolveTemplateUseCase(
                unitOfWork, overrides, defaults);
    }

    @Bean
    dev.ngb.notification.dispatch.application.notification.command.SaveTemplateOverrideUseCase saveTemplateOverrideUseCase(
            dev.ngb.core.runtime.UnitOfWork unitOfWork,
            dev.ngb.notification.dispatch.application.notification.port.TemplateOverrideRepository overrides,
            NotificationSettings settings) {
        return new dev.ngb.notification.dispatch.application.notification.command.SaveTemplateOverrideUseCase(
                unitOfWork, overrides, settings.templates().maxPartBytes());
    }
}
