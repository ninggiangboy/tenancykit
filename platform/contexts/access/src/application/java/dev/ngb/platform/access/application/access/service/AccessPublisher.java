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

    /** Publishes the user's next access state, bumping the version a consumer compares against. */
    public void publishFor(User user) {
        user.accessChanged();
        users.update(user);
        events.publish(new AccessEvents.UserAccessChanged(
                ids.newId(), user, access.effectivePermissions(user.id().value()), clock.now()));
    }

    /** Recomputes every user holding the role, in the same transaction as the role change. */
    public void publishForRole(RoleId roleId) {
        for (UUID userId : access.userIdsForRole(roleId.value())) {
            users.findById(new UserId(userId)).ifPresent(this::publishFor);
        }
    }
}
