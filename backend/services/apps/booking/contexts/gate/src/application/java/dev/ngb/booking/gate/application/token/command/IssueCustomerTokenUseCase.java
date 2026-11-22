package dev.ngb.booking.gate.application.token.command;

import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.gate.application.settings.service.GateSettingsService;
import dev.ngb.booking.gate.domain.token.CustomerClaims;
import dev.ngb.booking.gate.domain.token.CustomerTokens;
import dev.ngb.booking.gate.domain.token.GateScope;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mints a short-lived token for one customer. The Tenant decides who gets one; the token is opaque to the browser and
 * binds the tenant, the customer, the scopes it names and, optionally, one offering.
 */
public class IssueCustomerTokenUseCase {

    public record Command(
            String customerRef, List<String> scopes, UUID offeringId, Integer ttlSeconds) {}

    public record Result(String token, Instant expiresAt, String keyId) {}

    private final UnitOfWork unitOfWork;
    private final GateSettingsService settings;
    private final CalendarReader calendars;
    private final CustomerTokens.Keys keys;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final int appTokenCapSeconds;

    public IssueCustomerTokenUseCase(
            UnitOfWork unitOfWork,
            GateSettingsService settings,
            CalendarReader calendars,
            CustomerTokens.Keys keys,
            TenantContext tenantContext,
            Clock clock,
            int appTokenCapSeconds) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.calendars = calendars;
        this.keys = keys;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.appTokenCapSeconds = appTokenCapSeconds;
    }

    public Result execute(Command command) {
        String customerRef = customerRef(command.customerRef());
        Set<GateScope> scopes = scopes(command.scopes());
        if (command.offeringId() != null
                && unitOfWork.transactional(() -> calendars.offering(command.offeringId())).isEmpty()) {
            throw new NotFoundException("No offering exists here with that id.");
        }
        int cap = Math.min(appTokenCapSeconds, settings.get().maxTokenTtlSeconds());
        int ttl = command.ttlSeconds() == null ? Math.min(600, cap) : command.ttlSeconds();
        if (ttl < 1 || ttl > cap) {
            throw new IllegalArgumentException("ttlSeconds must be between 1 and " + cap);
        }
        Instant expiresAt = clock.now().plusSeconds(ttl);
        String token = CustomerTokens.sign(
                new CustomerClaims(
                        tenantContext.requireTenant(), customerRef, scopes, command.offeringId(), expiresAt,
                        keys.activeKeyId()),
                keys);
        return new Result(token, expiresAt, keys.activeKeyId());
    }

    private static String customerRef(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > 200) {
            throw new IllegalArgumentException("customerRef must be between 1 and 200 characters");
        }
        return raw.strip();
    }

    private static Set<GateScope> scopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("a customer token needs at least one scope");
        }
        Set<GateScope> scopes = EnumSet.noneOf(GateScope.class);
        for (String scope : requested) {
            try {
                scopes.add(GateScope.valueOf(scope));
            } catch (RuntimeException unknown) {
                throw new IllegalArgumentException("unknown customer scope: " + scope);
            }
        }
        return scopes;
    }
}
