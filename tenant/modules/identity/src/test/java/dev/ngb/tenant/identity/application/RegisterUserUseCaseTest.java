package dev.ngb.tenant.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.api.account.AccountListener;
import dev.ngb.tenant.identity.api.account.RegisteredAccount;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RegisterUserUseCaseTest {

    private static final AccountListener NO_LISTENER = new AccountListener() {
        @Override
        public void registered(RegisteredAccount account) {}
    };

    private final Fakes.Users users = new Fakes.Users();
    private final InMemoryUnitOfWork unitOfWork = new InMemoryUnitOfWork(users);
    private final RegisterUserUseCase register = new RegisterUserUseCase(
            users,
            new Fakes.Hasher(),
            unitOfWork,
            () -> Instant.parse("2026-11-11T00:00:00Z"),
            () -> UUID.randomUUID(),
            NO_LISTENER);

    @Test
    void registersAnActiveUserWithANormalizedEmailAndHashedPassword() {
        User user = register.execute(new RegisterUserUseCase.Command(" Ada@Example.COM ", " Ada ", "correct-horse", Role.VIEWER));

        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(user.displayName()).isEqualTo("Ada");
        assertThat(user.passwordHash()).isEqualTo("hash:correct-horse");
        assertThat(user.active()).isTrue();
        assertThat(users.anyExists()).isTrue();
    }

    @Test
    void theFirstSelfRegistrationBecomesTheAdministrator() {
        User first = register.execute(new RegisterUserUseCase.Command("ada@example.com", "Ada", "correct-horse", null));

        assertThat(first.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void everyLaterSelfRegistrationBecomesAViewer() {
        register.execute(new RegisterUserUseCase.Command("ada@example.com", "Ada", "correct-horse", null));

        User later = register.execute(new RegisterUserUseCase.Command("grace@example.com", "Grace", "correct-horse", null));

        assertThat(later.role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void refusesADuplicateOfADifferentlySpelledEmail() {
        register.execute(new RegisterUserUseCase.Command("ada@example.com", "Ada", "correct-horse", Role.VIEWER));

        assertThatThrownBy(() -> register.execute(
                        new RegisterUserUseCase.Command(" ADA@Example.com ", "Ada again", "another-password", Role.VIEWER)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void refusesAShortPassword() {
        assertThatThrownBy(() -> register.execute(
                        new RegisterUserUseCase.Command("ada@example.com", "Ada", "short", Role.VIEWER)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAnInvalidEmail() {
        assertThatThrownBy(() -> register.execute(
                        new RegisterUserUseCase.Command("not-an-email", "Ada", "correct-horse", Role.VIEWER)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aListenerThatFailsLeavesNoAccountBehind() {
        RegisterUserUseCase failing = new RegisterUserUseCase(
                users,
                new Fakes.Hasher(),
                unitOfWork,
                () -> Instant.parse("2026-11-11T00:00:00Z"),
                () -> UUID.randomUUID(),
                new AccountListener() {
                    @Override
                    public void registered(RegisteredAccount account) {
                        throw new IllegalStateException("the customer mapping is down");
                    }
                });

        assertThatThrownBy(() -> failing.execute(
                        new RegisterUserUseCase.Command("ada@example.com", "Ada", "correct-horse", null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(users.anyExists()).isFalse();
    }

    private static final class InMemoryUnitOfWork implements UnitOfWork {

        private final Fakes.Users users;

        private InMemoryUnitOfWork(Fakes.Users users) {
            this.users = users;
        }

        @Override
        public <T> T transactional(Supplier<T> work) {
            List<User> snapshot = users.snapshot();
            try {
                return work.get();
            } catch (RuntimeException e) {
                users.restore(snapshot);
                throw e;
            }
        }
    }
}
