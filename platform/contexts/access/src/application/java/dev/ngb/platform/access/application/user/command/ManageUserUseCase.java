package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.user.exception.UserNotFoundException;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;

/** Enables, disables or renames a user; every effective change publishes the new access. */
public class ManageUserUseCase {

    public enum Action { ENABLE, DISABLE, RENAME }

    public record Command(UserId userId, Action action, String newName, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final AccessPublisher access;

    public ManageUserUseCase(UnitOfWork unitOfWork, UserRepository users, AccessPublisher access) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.access = access;
    }

    public User execute(Command command) {
        return unitOfWork.transactional(() -> {
            User user = users.lockById(command.userId()).orElseThrow(() -> new UserNotFoundException(command.userId().toString()));
            command.expected().check(user.version());
            boolean changed = switch (command.action()) {
                case ENABLE -> user.enable();
                case DISABLE -> user.disable();
                case RENAME -> user.rename(command.newName());
            };
            if (!changed) {
                return user;
            }
            users.update(user);
            access.publishFor(user);
            return user;
        });
    }
}
