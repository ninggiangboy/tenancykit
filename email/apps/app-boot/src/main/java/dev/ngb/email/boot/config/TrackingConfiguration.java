package dev.ngb.email.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.api.unsubscribe.UnsubscribeAddress;
import dev.ngb.email.sending.adapter.tracking.crypto.HmacTrackingTokens;
import dev.ngb.email.sending.adapter.tracking.persistence.JooqEmailLinkRepository;
import dev.ngb.email.sending.application.email.port.EmailEventRepository;
import dev.ngb.email.sending.application.email.port.EmailRepository;
import dev.ngb.email.sending.application.email.port.TrackingHook;
import dev.ngb.email.sending.application.tracking.command.RecordClickUseCase;
import dev.ngb.email.sending.application.tracking.command.RecordOpenUseCase;
import dev.ngb.email.sending.application.tracking.command.UnsubscribeUseCase;
import dev.ngb.email.sending.application.tracking.port.EmailLinkRepository;
import dev.ngb.email.sending.application.tracking.port.TrackingTokens;
import dev.ngb.email.sending.application.tracking.service.MarketingTracking;
import dev.ngb.email.sending.application.tracking.service.TrackingSettings;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the HMAC tokens, the link store and the public tracking use cases. */
@Configuration(proxyBeanMethods = false)
class TrackingConfiguration {

    @Bean
    TrackingSettings trackingSettings(EmailSettings settings) {
        EmailSettings.Tracking tracking = settings.tracking();
        if (tracking == null) {
            throw new IllegalStateException("email.tracking is required: the public endpoints cannot sign a token");
        }
        requireActiveKey(tracking);
        return new TrackingSettings(tracking.activeKeyId(), tracking.publicBaseUrl(), tracking.keys());
    }

    /**
     * A missing tracking key is fatal. The key signs every pixel, click and unsubscribe token, and a blank one signs
     * with a value anyone can reproduce: a recipient could mint a token for any email in any tenant. Configuration
     * carries no default for that reason, so a deployment that forgets {@code EMAIL_TRACKING_SECRET} fails at boot
     * instead of running with a well-known secret.
     */
    private static void requireActiveKey(EmailSettings.Tracking tracking) {
        Map<String, String> keys = tracking.keys();
        String active = keys == null ? null : keys.get(tracking.activeKeyId());
        if (active == null || active.isBlank()) {
            throw new IllegalStateException(
                    "email.tracking.keys must define a non-blank " + tracking.activeKeyId()
                            + ": set EMAIL_TRACKING_SECRET");
        }
    }

    @Bean
    TrackingTokens trackingTokens(TrackingSettings settings) {
        return new HmacTrackingTokens(settings);
    }

    @Bean
    EmailLinkRepository emailLinkRepository(DSLContext dsl) {
        return new JooqEmailLinkRepository(dsl);
    }

    @Bean
    TrackingHook trackingHook(
            TrackingTokens tokens,
            TrackingSettings settings,
            EmailLinkRepository links,
            IdGenerator ids,
            Clock clock) {
        return new MarketingTracking(tokens, settings, links, ids, clock);
    }

    @Bean
    RecordOpenUseCase recordOpenUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailRepository emails,
            EmailEventRepository events,
            IdGenerator ids,
            Clock clock) {
        return new RecordOpenUseCase(unitOfWork, tenantContext, tokens, emails, events, ids, clock);
    }

    @Bean
    RecordClickUseCase recordClickUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailLinkRepository links,
            EmailRepository emails,
            EmailEventRepository events,
            IdGenerator ids,
            Clock clock) {
        return new RecordClickUseCase(
                unitOfWork, tenantContext, tokens, links, emails, events, ids, clock);
    }

    @Bean
    UnsubscribeUseCase unsubscribeUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailRepository emails,
            EmailEventRepository events,
            UnsubscribeAddress unsubscribeAddress,
            IdGenerator ids,
            Clock clock) {
        return new UnsubscribeUseCase(
                unitOfWork, tenantContext, tokens, emails, events, unsubscribeAddress, ids, clock);
    }
}
