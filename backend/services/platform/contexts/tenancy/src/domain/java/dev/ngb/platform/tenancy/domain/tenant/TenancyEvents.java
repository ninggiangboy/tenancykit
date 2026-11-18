package dev.ngb.platform.tenancy.domain.tenant;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What the tenancy context announces. The payloads are the state-transfer contract in contracts/events/platform. */
public final class TenancyEvents {

    private TenancyEvents() {}

    /** The full current state of one Tenant. A consumer upserts when {@code version} is newer. */
    public record TenantChanged(UUID eventId, Tenant tenant, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.TENANT_CHANGED;

        @Override
        public TenantId tenantId() {
            return tenant.id();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.tenant(tenant.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", tenant.id().toString());
            payload.put("code", tenant.code().value());
            payload.put("name", tenant.name());
            payload.put("status", tenant.status().name());
            payload.put("created_at", tenant.createdAt().toString());
            payload.put("version", tenant.version());
            return payload;
        }
    }

    /** The full current state of one hostname mapping. */
    public record TenantDomainChanged(UUID eventId, TenantDomain domain, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.TENANT_DOMAIN_CHANGED;

        @Override
        public TenantId tenantId() {
            return domain.tenantId().orElse(dev.ngb.core.tenancy.RequestScope.PLATFORM_TENANT_ID);
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.tenantDomain(domain.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", domain.id().toString());
            payload.put("tenant_id", domain.tenantId().map(TenantId::toString).orElse(null));
            payload.put("hostname", domain.hostname().value());
            payload.put("kind", domain.kind().name());
            payload.put("created_at", domain.createdAt().toString());
            payload.put("version", domain.version());
            return payload;
        }
    }
}
