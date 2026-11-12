package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.user.exception.UserNotFoundException;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;

/** Grants or revokes one direct permission on a user, outside any role. */
public class ManageUserPermissionsUseCase {

    public record Command(UserId userId, String permissionCode, boolean grant) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final AccessRepository access;
    private final PermissionCatalog catalog;
    private final AccessPublisher publisher;

    public ManageUserPermissionsUseCase(
            UnitOfWork unitOfWork, UserRepository users, AccessRepository access, PermissionCatalog catalog, AccessPublisher publisher) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.access = access;
        this.catalog = catalog;
        this.publisher = publisher;
    }

    public User execute(Command command) {
        return unitOfWork.transactional(() -> {
            User user = users.findById(command.userId()).orElseThrow(() -> new UserNotFoundException(command.userId().toString()));
            var permission = catalog.find(command.permissionCode())
                    .orElseThrow(() -> new UnknownPermissionException(command.permissionCode(), "is not in the catalog"));
            if (!permission.validIn(user.scope())) {
                throw new UnknownPermissionException(command.permissionCode(), "is not valid in " + user.scope() + " scope");
            }
            if (command.grant()) {
                access.grant(user.tenantId().value(), user.id().value(), command.permissionCode());
            } else {
                access.revoke(user.tenantId().value(), user.id().value(), command.permissionCode());
            }
            publisher.publishFor(user);
            return user;
        });
    }
}
