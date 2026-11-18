package dev.ngb.platform.access.api.user;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The console's {@code /me}: who the caller is and what they may do across the suite. */
public class MeQueries {

    public record MeView(UserView user, List<String> permissions) {}

    private final UnitOfWork unitOfWork;
    private final UserReadRepository users;

    public MeQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        this.unitOfWork = unitOfWork;
        this.users = users;
    }

    /** The actor a verified token names; the converter calls this before the request reaches a controller. */
    public Optional<MeView> findBySubject(String keycloakSubject) {
        return unitOfWork.transactional(() -> users.findByKeycloakSubject(keycloakSubject)
                .map(user -> new MeView(user, users.effectivePermissions(user.id()).stream().sorted().toList())));
    }

    /**
     * The caller the request already resolved, read by user id: the Keycloak subject named the actor, and a display
     * name is not a subject.
     */
    public Optional<MeView> findById(UUID id) {
        return unitOfWork.transactional(() -> users.findById(id)
                .map(user -> new MeView(user, users.effectivePermissions(user.id()).stream().sorted().toList())));
    }
}
