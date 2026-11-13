package dev.ngb.platform.access.domain.user;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** One user's effective access, recomputed after any change that can alter it. Consumers store one row per user. */
public final class AccessEvents {

    private AccessEvents() {}

    public record UserAccessChanged(UUID eventId, User user, Set<String> permissions, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.USER_ACCESS_CHANGED;

        public UserAccessChanged {
            permissions = Set.copyOf(permissions);
        }

        @Override
        public TenantId tenantId() {
            return user.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.user(user.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", user.id().toString());
            payload.put("tenant_id", user.tenantId().toString());
            payload.put("scope", user.scope().name());
            payload.put("keycloak_subject", user.keycloakSubject());
            payload.put("email", user.email());
            payload.put("display_name", user.displayName());
            payload.put("status", user.status().name());
            payload.put("permissions", new ArrayList<>(new TreeSet<>(permissions)));
            payload.put("version", user.version());
            return payload;
        }
    }

    /** The permission list in a stable order, for readers that build the same payload. */
    public static List<String> sorted(Set<String> permissions) {
        return List.copyOf(new TreeSet<>(permissions));
    }
}
