package dev.ngb.platform.access.application.role.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import java.util.Set;

/** Creates a custom role; every code must be catalogued and valid in the role's scope. */
public class CreateRoleUseCase {

    public record Command(TenantId tenantId, PermissionScope scope, String name, Set<String> permissions) {}

    private final UnitOfWork unitOfWork;
    private final RoleRepository roles;
    private final PermissionCatalog catalog;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateRoleUseCase(UnitOfWork unitOfWork, RoleRepository roles, PermissionCatalog catalog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
        this.catalog = catalog;
        this.ids = ids;
        this.clock = clock;
    }

    public Role execute(Command command) {
        return unitOfWork.transactional(() -> {
            command.permissions().forEach(code -> requireValid(code, command.scope()));
            Role role = Role.create(
                    new RoleId(ids.newId()), command.tenantId(), command.scope(), command.name(), false, command.permissions(), clock.now());
            roles.add(role);
            return role;
        });
    }

    private void requireValid(String code, PermissionScope scope) {
        var permission = catalog.find(code).orElseThrow(() -> new UnknownPermissionException(code, "is not in the catalog"));
        if (!permission.validIn(scope)) {
            throw new UnknownPermissionException(code, "is not valid in " + scope + " scope");
        }
    }
}
