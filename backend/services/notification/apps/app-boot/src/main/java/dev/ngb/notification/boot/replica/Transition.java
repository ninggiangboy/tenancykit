package dev.ngb.notification.boot.replica;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.platform.api.replica.UserView;
import java.util.UUID;

/**
 * One applied state-transfer event: the row before the upsert and the row after it.
 *
 * <p>State-transfer events carry the full current row and never say "this changed", so a rule compares the two
 * snapshots. The previous value is read before the upsert, inside the same transaction; reading it afterwards yields
 * the new state twice and every rule silently stops firing.
 *
 * @param previousUser null when this is the user's first grant row
 * @param previousTenant null when the tenant row is new
 * @param appCode the app an entitlement event names, null for every other stream
 * @param previousEntitlement null when the entitlement row is new
 */
public record Transition(
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
