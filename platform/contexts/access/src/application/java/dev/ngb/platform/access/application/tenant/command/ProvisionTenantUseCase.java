package dev.ngb.platform.access.application.tenant.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.tenancy.api.provisioning.TenantProvisioning;
import java.util.Set;
import java.util.UUID;

/**
 * Creates a Tenant, seeds the Tenant Administrator role with every tenant-scope code, and optionally creates the
 * first administrator assigned to it. Access depends on tenancy through its api only.
 */
public class ProvisionTenantUseCase {

    public record Command(String code, String name, String adminEmail, String adminTemporaryPassword) {}

    public record Provisioned(TenantId tenantId, String adminEmail) {}

    /** The privileged, platform-scoped actor that seeds a new tenant's roles and users. */
    private static final Actor PLATFORM_ACTOR = new Actor(
            ActorType.SERVICE, new UUID(0, 0), RequestScope.PLATFORM_TENANT_ID, PermissionScope.PLATFORM, "platform-provisioning", Set.of());

    private final TenantProvisioning tenants;
    private final CreateUserUseCase createUser;
    private final CreateRoleUseCase createRole;
    private final AccessRepository access;
    private final AccessPublisher publisher;
    private final PermissionCatalog catalog;
    private final UnitOfWork unitOfWork;

    public ProvisionTenantUseCase(
            TenantProvisioning tenants, CreateUserUseCase createUser, CreateRoleUseCase createRole, AccessRepository access,
            AccessPublisher publisher, PermissionCatalog catalog, UnitOfWork unitOfWork) {
        this.tenants = tenants;
        this.createUser = createUser;
        this.createRole = createRole;
        this.access = access;
        this.publisher = publisher;
        this.catalog = catalog;
        this.unitOfWork = unitOfWork;
    }

    public Provisioned execute(Command command) {
        TenantId tenantId = new TenantId(tenants.createTenant(command.code(), command.name()));
        var role = createRole.execute(PLATFORM_ACTOR, new CreateRoleUseCase.Command(
                tenantId, PermissionScope.TENANT, "Tenant Administrator", catalog.codesValidIn(PermissionScope.TENANT)));
        if (command.adminEmail() == null || command.adminEmail().isBlank()) {
            return new Provisioned(tenantId, null);
        }
        var admin = createUser.execute(PLATFORM_ACTOR, new CreateUserUseCase.Command(
                command.code(), tenantId, PermissionScope.TENANT, command.adminEmail(), "Tenant Administrator",
                command.adminTemporaryPassword()));
        unitOfWork.transactional(() -> {
            access.assign(tenantId.value(), admin.id().value(), role.id().value());
            publisher.publishFor(admin.id());
            return null;
        });
        return new Provisioned(tenantId, command.adminEmail());
    }
}
