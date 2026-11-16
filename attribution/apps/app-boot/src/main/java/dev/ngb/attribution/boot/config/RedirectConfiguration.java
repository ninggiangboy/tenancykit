package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.boot.web.EntitlementGate;
import dev.ngb.attribution.boot.web.RedirectController;
import dev.ngb.attribution.links.adapter.redirect.cache.LinkCache;
import dev.ngb.attribution.links.adapter.redirect.ratelimit.PublicRateLimiter;
import dev.ngb.attribution.links.adapter.redirect.suspect.SuspectTracker;
import dev.ngb.attribution.links.adapter.redirect.unlock.UnlockAttempts;
import dev.ngb.attribution.links.adapter.redirect.web.ClickFactory;
import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.redirect.service.RedirectDecider;
import dev.ngb.attribution.tracking.api.ingest.ClickIngest;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DailyVisitorHash;
import dev.ngb.core.visitor.GeoCountryReader;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The redirect path's wiring: the cache, the decision, the classifiers, the public limits and the token keys.
 *
 * <p>Everything here is per-process memory rather than shared state, because a redirect may not wait on a database or a
 * broker. The one exception is a capped link's counter, which is exact by design and documented as the throughput cost
 * of a cap.
 */
@Configuration(proxyBeanMethods = false)
class RedirectConfiguration {

    @Bean
    LinkCache linkCache(LinkRepository links, UnitOfWork unitOfWork, AttributionSettings settings) {
        AttributionSettings.Redirect redirect = settings.redirect();
        return new LinkCache(links, unitOfWork, redirect.cacheTtl(), redirect.cacheMaxSize());
    }

    @Bean
    RedirectDecider redirectDecider(LinkRepository links, RandomGenerator redirectRandom) {
        return new RedirectDecider(links, redirectRandom);
    }

    @Bean
    RandomGenerator redirectRandom() {
        return new Random();
    }

    @Bean
    DailyVisitorHash dailyVisitorHash(AttributionKeysConfiguration.AttributionKeys keys) {
        return new DailyVisitorHash(keys.visitorHashKey());
    }

    @Bean(destroyMethod = "close")
    GeoCountryReader geoCountryReader(AttributionKeysConfiguration.AttributionKeys keys) {
        return GeoCountryReader.open(keys.geoipPath());
    }

    @Bean
    ClickFactory clickFactory(
            Clock clock,
            IdGenerator ids,
            TenantContext tenantContext,
            DailyVisitorHash dailyVisitorHash,
            GeoCountryReader countries) {
        return new ClickFactory(clock, ids, tenantContext, dailyVisitorHash, countries);
    }

    @Bean
    PublicRateLimiter visitorRateLimiter(AttributionSettings settings) {
        return new PublicRateLimiter(
                settings.redirect().visitorPerMinute(), settings.redirect().unlockWindow());
    }

    @Bean
    PublicRateLimiter linkRateLimiter(AttributionSettings settings) {
        return new PublicRateLimiter(settings.redirect().linkPerMinute(), settings.redirect().unlockWindow());
    }

    @Bean
    SuspectTracker suspectTracker(AttributionSettings settings) {
        return new SuspectTracker(java.time.Duration.ofMinutes(1));
    }

    @Bean
    UnlockAttempts unlockAttempts(AttributionSettings settings) {
        return new UnlockAttempts(
                settings.redirect().unlockAttempts(), settings.redirect().unlockWindow());
    }

    @Bean
    EntitlementGate entitlementGate(
            SuiteEntitlements entitlements, TenantContext tenantContext, AttributionSettings settings) {
        return EntitlementGate.cached(entitlements, tenantContext, settings.redirect().cacheTtl());
    }

    @Bean
    RedirectController.ClickTokenKeys clickTokenKeys(AttributionKeysConfiguration.AttributionKeys keys) {
        return new RedirectController.ClickTokenKeys(keys.activeKeyId(), Map.copyOf(keys.clickKeys()));
    }

    @Bean
    RedirectController redirectController(
            LinkCache cache,
            RedirectDecider decider,
            ClickFactory clicks,
            ClickIngest ingest,
            ClickParamProvider clickParam,
            PasswordHasher passwords,
            PublicRateLimiter visitorRateLimiter,
            PublicRateLimiter linkRateLimiter,
            UnlockAttempts unlockAttempts,
            SuspectTracker suspect,
            EntitlementGate entitlements,
            RedirectController.ClickTokenKeys keys,
            TenantContext tenantContext,
            Clock clock) {
        return new RedirectController(
                cache, decider, clicks, ingest, clickParam, passwords, visitorRateLimiter, linkRateLimiter,
                unlockAttempts, suspect, entitlements, keys, tenantContext, clock);
    }
}
