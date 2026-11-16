package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.boot.web.ClickDailyLimiterImpl;
import dev.ngb.attribution.boot.web.OriginPolicyImpl;
import dev.ngb.attribution.links.adapter.settings.persistence.JooqSettingsRepository;
import dev.ngb.attribution.links.application.settings.command.UpdateSettingsUseCase;
import dev.ngb.attribution.links.application.settings.query.SettingsReadRepository;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqErasureStore;
import dev.ngb.attribution.boot.web.CollectController;
import dev.ngb.attribution.boot.web.CollectRateLimiter;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqPageViewStore;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqTouchpointReader;
import dev.ngb.attribution.tracking.api.chain.TouchpointReader;
import dev.ngb.attribution.tracking.application.collect.command.CollectPageViewsUseCase;
import dev.ngb.attribution.tracking.api.erasure.ConversionPersonEraser;
import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import dev.ngb.attribution.tracking.application.collect.port.PageViewStore;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The collection path's wiring: the page-view store, the collect use case, the origin policy, the per-click limiter, the
 * settings and the erasure endpoint.
 *
 * <p>The collect use case verifies a click token with the deployment's click keys, the same ones the redirect signed
 * with, so a page view can only name a click this app issued. The click keys are shared with the redirect through the
 * one key configuration, not copied.
 */
@Configuration(proxyBeanMethods = false)
class CollectionConfiguration {

    @Bean
    PageViewStore pageViewStore(DSLContext dsl) {
        return new JooqPageViewStore(dsl);
    }

    @Bean
    ErasureStore erasureStore(DSLContext dsl) {
        return new JooqErasureStore(dsl);
    }

    @Bean
    TouchpointReader touchpointReader(DSLContext dsl) {
        return new JooqTouchpointReader(dsl);
    }

    @Bean
    CollectPageViewsUseCase collectPageViewsUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            PageViewStore pageViewStore,
            IdGenerator ids,
            AttributionKeysConfiguration.AttributionKeys keys) {
        return new CollectPageViewsUseCase(
                unitOfWork, tenantContext, pageViewStore, ids, Map.copyOf(keys.clickKeys()));
    }

    @Bean
    CollectController.OriginPolicy originPolicy(
            UnitOfWork unitOfWork,
            SettingsReadRepository settings,
            AttributionSettings attributionSettings) {
        return new OriginPolicyImpl(
                unitOfWork,
                settings,
                attributionSettings.redirect().cacheTtl(),
                attributionSettings.defaults().clickParam(),
                attributionSettings.defaults().defaultWindowDays());
    }

    @Bean
    CollectController.ClickDailyLimiter clickDailyLimiter() {
        return new ClickDailyLimiterImpl(ClickDailyLimiterImpl.retention());
    }

    @Bean
    CollectRateLimiter collectRateLimiter(AttributionSettings settings) {
        return new CollectRateLimiter(
                settings.redirect().visitorPerMinute(), settings.redirect().unlockWindow());
    }


    @Bean
    JooqSettingsRepository settingsRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqSettingsRepository(dsl, tenantContext);
    }

    @Bean
    UpdateSettingsUseCase updateSettingsUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            JooqSettingsRepository settings,
            Clock clock) {
        return new UpdateSettingsUseCase(unitOfWork, tenantContext, settings, clock);
    }


}
