package dev.ngb.platform.integration.domain.tenantsystem;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What the integration context announces. The payload is the state-transfer contract in contracts/events/platform. */
public final class TenantSystemEvents {

    private TenantSystemEvents() {}

    /** The full current state of one Tenant System. A consumer upserts when {@code version} is newer. */
    public record TenantSystemChanged(UUID eventId, TenantSystem system, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.TENANT_SYSTEM_CHANGED;

        @Override
        public TenantId tenantId() {
            return system.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.tenantSystem(system.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", system.id().toString());
            payload.put("tenant_id", system.tenantId().toString());
            payload.put("name", system.name());
            payload.put("purpose", system.purpose().name());
            payload.put("status", system.status().name());
            payload.put("created_at", system.createdAt().toString());
            payload.put("version", system.version());
            return payload;
        }
    }
}
