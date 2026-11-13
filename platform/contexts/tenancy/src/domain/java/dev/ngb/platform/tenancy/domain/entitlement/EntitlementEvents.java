package dev.ngb.platform.tenancy.domain.entitlement;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What the entitlement side announces: whether one Tenant may use one app. */
public final class EntitlementEvents {

    private EntitlementEvents() {}

    public record EntitlementChanged(UUID eventId, AppEntitlement entitlement, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.ENTITLEMENT_CHANGED;

        @Override
        public TenantId tenantId() {
            return entitlement.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.entitlement(entitlement.tenantId(), entitlement.appCode());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenant_id", entitlement.tenantId().toString());
            payload.put("app_code", entitlement.appCode());
            payload.put("enabled", entitlement.isEnabled());
            payload.put("version", entitlement.version());
            return payload;
        }
    }
}
