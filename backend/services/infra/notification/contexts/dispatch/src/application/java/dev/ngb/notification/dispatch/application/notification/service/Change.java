package dev.ngb.notification.dispatch.application.notification.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.platform.api.replica.UserView;
import java.util.UUID;

/**
 * One applied state-transfer event, in the dispatch context's own vocabulary: the snapshot before the upsert and the
 * snapshot after it. The boot layer maps its replica {@code Transition} onto this, so the catalog never depends on the
 * wiring layer.
 */
public record Change(
        UUID eventId,
        String eventType,
        TenantId tenantId,
        UserView previousUser,
        UserView currentUser,
        TenantView previousTenant,
        TenantView currentTenant,
        String appCode,
        Boolean previousEntitlement,
        Boolean currentEntitlement) {}
