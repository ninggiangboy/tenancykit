package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import java.util.UUID;

/** One user's locale and muted types; an absent row is an empty preference, never an error. */
public interface NotificationPreferenceRepository {

    PreferenceView find(TenantId tenantId, UUID userId);

    void save(TenantId tenantId, UUID userId, PreferenceView preference);
}
