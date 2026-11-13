package dev.ngb.platform.access.api.user;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.Optional;

/** The console's {@code /me}: who the caller is and what they may do across the suite. */
public class MeQueries {

    public record MeView(UserView user, List<String> permissions) {}

    private final UnitOfWork unitOfWork;
    private final UserReadRepository users;

    public MeQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        this.unitOfWork = unitOfWork;
        this.users = users;
    }

    public Optional<MeView> find(String keycloakSubject) {
        return unitOfWork.transactional(() -> users.findByKeycloakSubject(keycloakSubject)
                .map(user -> new MeView(user, users.effectivePermissions(user.id()).stream().sorted().toList())));
    }
}
