package dev.ngb.booking.boot.config;

import dev.ngb.booking.gate.adapter.queue.persistence.CachedQueueWatermarks;
import dev.ngb.booking.gate.adapter.queue.persistence.JooqQueueTicketRepository;
import dev.ngb.booking.gate.adapter.settings.persistence.JooqGateSettingsRepository;
import dev.ngb.booking.gate.adapter.publicgate.web.ClientFingerprint;
import dev.ngb.booking.gate.adapter.publicgate.web.PublicRateLimiter;
import dev.ngb.booking.gate.application.service.AdmissionControl;
import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.booking.gate.application.queue.port.WatermarkCache;
import dev.ngb.booking.gate.application.queue.service.QueueService;
import dev.ngb.booking.gate.application.service.GateLimits;
import dev.ngb.booking.gate.application.settings.port.GateSettingsRepository;
import dev.ngb.booking.gate.application.settings.service.GateSettingsService;
import dev.ngb.booking.gate.application.settings.query.GateTimingView;
import dev.ngb.booking.gate.application.token.command.IssueCustomerTokenUseCase;
import dev.ngb.booking.gate.domain.token.CustomerTokens;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the public gate: the customer-token keys, the tenant's origin policy, the per-instance limits and the token
 * issuer. The keys have no shipped secret, so a deployment that forgets one fails at startup instead of minting tokens
 * anyone can forge.
 */
@Configuration(proxyBeanMethods = false)
class GateConfiguration {

    @Bean
    CustomerTokens.Keys customerTokenKeys(BookingSettings settings) {
        BookingSettings.Gate gate = settings.gate();
        if (gate.keys() == null || gate.keys().isEmpty()) {
            throw new IllegalStateException("booking.gate.keys is empty: a customer token needs a configured key");
        }
        for (Map.Entry<String, String> key : gate.keys().entrySet()) {
            if (key.getValue() == null || key.getValue().isBlank()) {
                throw new IllegalStateException(
                        "booking.gate.keys." + key.getKey() + " must define a non-blank secret");
            }
        }
        if (!gate.keys().containsKey(gate.activeKeyId())) {
            throw new IllegalStateException(
                    "booking.gate.active-key-id names no configured key: " + gate.activeKeyId());
        }
        return new CustomerTokens.Keys(gate.activeKeyId(), gate.keys());
    }

    @Bean
    GateSettingsRepository gateSettingsRepository(DSLContext dsl, TenantContext tenantContext, Clock clock) {
        return new JooqGateSettingsRepository(dsl, tenantContext, clock);
    }

    @Bean
    GateSettingsService gateSettingsService(
            UnitOfWork unitOfWork, GateSettingsRepository settings, BookingSettings booking) {
        return new GateSettingsService(
                unitOfWork, settings, (int) booking.timings().tokenTtlCapSeconds());
    }

    @Bean
    IssueCustomerTokenUseCase issueCustomerTokenUseCase(
            UnitOfWork unitOfWork,
            GateSettingsService settings,
            CalendarReader calendars,
            CustomerTokens.Keys keys,
            TenantContext tenantContext,
            Clock clock,
            BookingSettings booking) {
        return new IssueCustomerTokenUseCase(
                unitOfWork, settings, calendars, keys, tenantContext, clock,
                (int) booking.timings().tokenTtlCapSeconds());
    }

    @Bean
    GateLimits gateLimits(BookingSettings settings) {
        return new GateLimits(
                settings.timings().rateLimitPerMinute(),
                settings.timings().admissionCap(),
                settings.gate().maxBodyBytes());
    }

    @Bean
    AdmissionControl admissionControl(GateLimits limits) {
        return new AdmissionControl(limits.admissionCap());
    }

    @Bean
    PublicRateLimiter publicRateLimiter(GateLimits limits) {
        return new PublicRateLimiter(limits.rateLimitPerMinute());
    }

    @Bean
    ClientFingerprint clientFingerprint(CustomerTokens.Keys keys) {
        return new ClientFingerprint(keys.keys().get(keys.activeKeyId()));
    }

    @Bean
    QueueTicketRepository queueTicketRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqQueueTicketRepository(dsl, tenantContext);
    }

    @Bean
    WatermarkCache watermarkCache(QueueTicketRepository tickets, Clock clock) {
        return new CachedQueueWatermarks(tickets, clock);
    }

    @Bean
    QueueService queueService(
            UnitOfWork unitOfWork,
            QueueTicketRepository tickets,
            WatermarkCache watermarks,
            CustomerTokens.Keys keys,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new QueueService(unitOfWork, tickets, watermarks, keys, tenantContext, ids, clock);
    }

    @Bean
    GateTimingView gateTimingView(BookingSettings settings) {
        BookingSettings.Timings timings = settings.timings();
        return new GateTimingView(
                timings.holdTtlSeconds(),
                timings.maxExtensionSeconds(),
                timings.graceSeconds(),
                timings.offerWindowSeconds(),
                timings.horizonDays(),
                timings.tokenTtlCapSeconds(),
                timings.rateLimitPerMinute(),
                timings.admissionCap(),
                timings.waitlistCapPerTarget());
    }
}
