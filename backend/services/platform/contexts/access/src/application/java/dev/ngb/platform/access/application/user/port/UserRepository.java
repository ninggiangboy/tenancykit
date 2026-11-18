package dev.ngb.platform.access.application.user.port;

import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.Optional;

/** Write side of users. */
public interface UserRepository {

    void add(User user);

    void update(User user);

    Optional<User> findById(UserId id);

    /**
     * Reads the user under a row lock, so a version bump that follows cannot race another one.
     *
     * @return empty when the user does not exist
     */
    Optional<User> lockById(UserId id);

    Optional<User> findByKeycloakSubject(String subject);

    Optional<User> findByEmail(dev.ngb.core.id.TenantId tenantId, String email);
}
