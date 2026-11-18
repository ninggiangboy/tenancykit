package dev.ngb.platform.access.application.access.service;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform.access.domain.user.AccessEvents;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.UUID;

/**
 * Publishes one {@code user-access.changed} per affected user. It is called inside the same unit of work as the
 * change, so a consumer never sees an event for a change that rolled back.
 *
 * <p>A version bump happens only under the user's row lock: the lock is taken first, then the version is bumped, then
 * the row is written and the permissions are computed. Two concurrent changes on one user therefore publish two
 * distinct versions, and the replica never drops the second state as a duplicate.
 */
public class AccessPublisher {

    private final AccessRepository access;
    private final UserRepository users;
    private final EventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public AccessPublisher(
            AccessRepository access, UserRepository users, EventPublisher events, IdGenerator ids, Clock clock) {
        this.access = access;
        this.users = users;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /** Publishes the first state of a user that was just created, at version 1. */
    public void publishNew(User user) {
        events.publish(new AccessEvents.UserAccessChanged(
                ids.newId(), user, access.effectivePermissions(user.id().value()), clock.now()));
    }

    /** Locks the user, then publishes its next access state. Use it when the caller holds no lock yet. */
    public void publishFor(UserId userId) {
        users.lockById(userId).ifPresent(this::publishFor);
    }

    /**
     * Bumps and publishes a user the caller already holds the row lock on.
     *
     * <p>The permission set is computed after the update, so this transaction's own write and every change committed
     * before the lock are both visible.
     */
    public void publishFor(User user) {
        user.accessChanged();
        users.update(user);
        events.publish(new AccessEvents.UserAccessChanged(
                ids.newId(), user, access.effectivePermissions(user.id().value()), clock.now()));
    }

    /**
     * Recomputes every user holding the role, in the same transaction as the role change. Holders are locked in
     * ascending id order, and the caller must already hold the role lock.
     */
    public void publishForRole(RoleId roleId) {
        for (UUID userId : access.userIdsForRole(roleId.value())) {
            publishFor(new UserId(userId));
        }
    }
}
