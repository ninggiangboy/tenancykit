package dev.ngb.email.boot.replica;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.email.platform.api.replica.TenantDirectory;
import dev.ngb.email.platform.api.replica.TenantView;
import dev.ngb.email.platform.api.replica.UserDirectory;
import dev.ngb.email.platform.api.replica.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the replica current and announces the transition.
 *
 * <p>It is deliberately not {@link dev.ngb.core.replica.ReplicaEventHandler}: that handler is a one-line
 * {@code writer.apply(...)} with no hook, so copying it would leave nowhere for the previous read and the dispatch
 * call. This handler reads the previous row, applies the guarded upsert, reads the stored row back, and hands both
 * snapshots to every {@link TransitionSink} — all inside the dispatcher's transaction.
 */
public class PlatformEventsHandler implements InboundEventHandler {

    /** One consumer name, distinct from the shared replica consumer, so the inbox key is this service's own. */
    public static final String CONSUMER = "email-platform-events";

    private static final Logger LOG = LoggerFactory.getLogger(PlatformEventsHandler.class);

    private final ReplicaWriter writer;
    private final TenantDirectory tenants;
    private final UserDirectory users;
    private final List<TransitionSink> sinks;

    public PlatformEventsHandler(
            ReplicaWriter writer, TenantDirectory tenants, UserDirectory users, List<TransitionSink> sinks) {
        this.writer = writer;
        this.tenants = tenants;
        this.users = users;
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean accepts(String type) {
        return ReplicaStream.byEventType(type).isPresent();
    }

    @Override
    public void handle(EventEnvelope event) {
        ReplicaStream stream = ReplicaStream.byEventType(event.type())
                .orElseThrow(() -> new IllegalArgumentException("unsubscribed event type " + event.type()));
        Map<String, Object> payload = event.payload();
        UUID eventId = event.eventId();
        switch (stream) {
            case USER_ACCESS -> userAccess(eventId, event.type(), payload);
            case ENTITLEMENTS -> entitlement(eventId, event.type(), payload);
            case TENANTS -> tenant(eventId, event.type(), payload);
            default -> writer.apply(event.type(), payload);
        }
    }

    private void userAccess(UUID eventId, String type, Map<String, Object> payload) {
        UUID userId = UUID.fromString(String.valueOf(payload.get("user_id")));
        UserView previous = users.user(userId).orElse(null);
        writer.apply(type, payload);
        UserView current = users.user(userId).orElse(null);
        if (current == null) {
            LOG.warn("user-access event {} was not stored", eventId);
            return;
        }
        announce(new Transition(
                eventId, type, current.tenantId(), previous, current, null, null, null, null, null));
    }

    private void entitlement(UUID eventId, String type, Map<String, Object> payload) {
        TenantId tenantId = new TenantId(UUID.fromString(String.valueOf(payload.get("tenant_id"))));
        String appCode = String.valueOf(payload.get("app_code"));
        Boolean previous = tenants.entitlementEnabled(tenantId, appCode).orElse(null);
        writer.apply(type, payload);
        Boolean current = tenants.entitlementEnabled(tenantId, appCode).orElse(null);
        TenantView tenant = tenants.tenant(tenantId).orElse(null);
        announce(new Transition(
                eventId, type, tenantId, null, null, tenant, tenant, appCode, previous, current));
    }

    private void tenant(UUID eventId, String type, Map<String, Object> payload) {
        TenantId tenantId = new TenantId(UUID.fromString(String.valueOf(payload.get("id"))));
        TenantView previous = tenants.tenant(tenantId).orElse(null);
        writer.apply(type, payload);
        TenantView current = tenants.tenant(tenantId).orElse(null);
        announce(new Transition(eventId, type, tenantId, null, null, previous, current, null, null, null));
    }

    private void announce(Transition transition) {
        for (TransitionSink sink : sinks) {
            sink.after(transition);
        }
    }
}
