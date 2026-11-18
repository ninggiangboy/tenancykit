package dev.ngb.platform.access.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code /me} answers for the user the request already resolved. Looking up by display name instead of user id missed
 * every user whose display name is not their Keycloak subject.
 */
class MeQueriesTest {

    private static final UUID USER_ID = UUID.fromString("0193f0a0-0000-7000-8000-000000000042");
    private static final UnitOfWork IMMEDIATE = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    @Test
    void findsTheCallerByIdAndSortsThePermissions() {
        UserView user = new UserView(
                USER_ID, UUID.fromString("0193f0a0-0000-7000-8000-000000000001"), "PLATFORM", "kc-subject",
                "admin@example.test", "Some Admin", "ACTIVE", 3);
        MeQueries queries = new MeQueries(IMMEDIATE, new StubUsers(user, Set.of("platform:tenant:read", "platform:user:manage")));

        Optional<MeQueries.MeView> me = queries.findById(USER_ID);

        assertThat(me).isPresent();
        assertThat(me.get().user().displayName()).isEqualTo("Some Admin");
        assertThat(me.get().permissions()).containsExactly("platform:tenant:read", "platform:user:manage");
    }

    @Test
    void aUserThatIsNotThereIsEmptyRatherThanAnException() {
        MeQueries queries = new MeQueries(IMMEDIATE, new StubUsers(null, Set.of()));

        assertThat(queries.findById(USER_ID)).isEmpty();
    }

    private record StubUsers(UserView user, Set<String> permissions) implements UserReadRepository {

        @Override
        public Page<UserView> list(ListQuery query, UUID tenantScope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserView> findById(UUID id) {
            return user != null && user.id().equals(id) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public Optional<UserView> findByKeycloakSubject(String subject) {
            return user != null && user.keycloakSubject().equals(subject) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public Set<String> effectivePermissions(UUID userId) {
            return permissions;
        }

        @Override
        public List<UserAccessView> pageAccess(UUID after, int limit) {
            return List.of();
        }
    }
}
