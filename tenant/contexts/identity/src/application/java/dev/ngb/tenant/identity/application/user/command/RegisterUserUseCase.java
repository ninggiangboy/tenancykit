package dev.ngb.tenant.identity.application.user.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.api.account.AccountListener;
import dev.ngb.tenant.identity.api.account.RegisteredAccount;
import dev.ngb.tenant.identity.application.user.port.PasswordHasher;
import dev.ngb.tenant.identity.application.user.port.UserRepository;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;

/**
 * Registers a local account with its own credentials. A command without a role registers an account
 * for itself: the first account of this tenant becomes its administrator and every later one a
 * viewer, decided inside the same unit of work that saves the account.
 */
public class RegisterUserUseCase {

    public static final int MIN_PASSWORD_LENGTH = 8;

    public record Command(String email, String displayName, String password, Role role) {}

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final IdGenerator ids;
    private final AccountListener listener;

    public RegisterUserUseCase(
            UserRepository users,
            PasswordHasher hasher,
            UnitOfWork unitOfWork,
            Clock clock,
            IdGenerator ids,
            AccountListener listener) {
        this.users = users;
        this.hasher = hasher;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.ids = ids;
        this.listener = listener;
    }

    public User execute(Command command) {
        String email = User.normalizeEmail(command.email());
        if (email == null || email.isBlank()) {
            throw new BadRequestException("an email address is required");
        }
        if (command.password() == null || command.password().length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("the password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        return unitOfWork.transactional(() -> register(command, email));
    }

    private User register(Command command, String email) {
        if (users.emailExists(email)) {
            throw new ConflictException("an account with this email already exists");
        }
        Role role = command.role() == null ? selfRegistrationRole() : command.role();
        User user = User.register(
                ids, email, command.displayName(), hasher.hash(command.password()), role, clock.now());
        users.save(user);
        listener.registered(new RegisteredAccount(user.id(), user.displayName(), user.email()));
        return user;
    }

    private Role selfRegistrationRole() {
        return users.anyExists() ? Role.VIEWER : Role.ADMIN;
    }
}
