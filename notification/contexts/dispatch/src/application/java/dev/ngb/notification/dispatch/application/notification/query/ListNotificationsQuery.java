package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own inbox. The tenant comes from the request scope and the user from the authenticated actor, never
 * from a parameter, so reading another user's notification is not expressible.
 */
public class ListNotificationsQuery {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    public record Page(List<NotificationView> items, long unreadCount, UUID nextBefore) {}

    private final NotificationReadRepository repository;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    public ListNotificationsQuery(
            NotificationReadRepository repository, TenantContext tenantContext, CurrentActor currentActor) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    public Page list(boolean unreadOnly, UUID before, int limit) {
        TenantId tenantId = tenantContext.requireTenant();
        UUID userId = currentActor.get().id();
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<NotificationView> items = repository.list(tenantId, userId, unreadOnly, before, capped);
        UUID next = items.size() == capped ? items.getLast().id() : null;
        return new Page(items, repository.unreadCount(tenantId, userId), next);
    }
}
