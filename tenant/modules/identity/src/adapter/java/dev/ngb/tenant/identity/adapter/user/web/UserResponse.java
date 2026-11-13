package dev.ngb.tenant.identity.adapter.user.web;

import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.application.user.query.UserView;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.tenant.identity.domain.user.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A local account as the identity API carries it: the one rendering of an account, from the aggregate a command
 * returned and from the view a query read, so both answers are the same body.
 */
public record UserResponse(UUID id, String email, String displayName, Role role, String status, Instant createdAt) {

    public static UserResponse of(User user) {
        return of(user.id(), user.email(), user.displayName(), user.role(), user.status(), user.createdAt());
    }

    public static UserResponse of(UserView user) {
        return of(user.id(), user.email(), user.displayName(), user.role(), user.status(), user.createdAt());
    }

    private static UserResponse of(UUID id, String email, String displayName, Role role, UserStatus status, Instant createdAt) {
        return new UserResponse(id, email, displayName, role, status.name(), createdAt);
    }
}
