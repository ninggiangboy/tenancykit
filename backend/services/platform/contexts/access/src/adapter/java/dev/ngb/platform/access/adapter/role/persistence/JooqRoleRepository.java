package dev.ngb.platform.access.adapter.role.persistence;

import static dev.ngb.platform_jooq.Tables.ROLES;
import static dev.ngb.platform_jooq.Tables.ROLE_PERMISSIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform_jooq.tables.records.RolesRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqRoleRepository implements RoleRepository {

    private final DSLContext dsl;

    public JooqRoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Role role) {
        dsl.insertInto(ROLES)
                .set(ROLES.ID, role.id().value())
                .set(ROLES.TENANT_ID, role.tenantId().value())
                .set(ROLES.SCOPE, role.scope().name())
                .set(ROLES.NAME, role.name())
                .set(ROLES.SYSTEM, role.isSystem())
                .set(ROLES.CREATED_AT, role.createdAt())
                .set(ROLES.VERSION, role.version())
                .execute();
        replacePermissions(role);
    }

    @Override
    public void update(Role role) {
        int updated = dsl.update(ROLES)
                .set(ROLES.NAME, role.name())
                .set(ROLES.VERSION, role.version())
                .where(ROLES.ID.eq(role.id().value()))
                .and(ROLES.VERSION.lt(role.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("role " + role.id() + " was not updated: version " + role.version() + " is not newer");
        }
        replacePermissions(role);
    }

    @Override
    public Optional<Role> findById(RoleId id) {
        return dsl.selectFrom(ROLES).where(ROLES.ID.eq(id.value())).fetchOptional(record -> toRole(record, permissionsOf(id.value())));
    }

    @Override
    public Optional<Role> lockForUpdate(RoleId id) {
        return dsl.selectFrom(ROLES).where(ROLES.ID.eq(id.value())).forNoKeyUpdate().fetchOptional(record -> toRole(record, permissionsOf(id.value())));
    }

    @Override
    public Optional<Role> lockForShare(RoleId id) {
        return dsl.selectFrom(ROLES).where(ROLES.ID.eq(id.value())).forShare().fetchOptional(record -> toRole(record, permissionsOf(id.value())));
    }

    @Override
    public Optional<Role> findByName(UUID tenantId, String name) {
        return dsl.selectFrom(ROLES)
                .where(ROLES.TENANT_ID.eq(tenantId))
                .and(ROLES.NAME.eq(name))
                .fetchOptional(record -> toRole(record, permissionsOf(record.getId())));
    }

    Set<String> permissionsOf(UUID roleId) {
        return new HashSet<>(dsl.select(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(roleId))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
    }

    private void replacePermissions(Role role) {
        dsl.deleteFrom(ROLE_PERMISSIONS).where(ROLE_PERMISSIONS.ROLE_ID.eq(role.id().value())).execute();
        List<String> codes = List.copyOf(role.permissions());
        if (!codes.isEmpty()) {
            var insert = dsl.insertInto(ROLE_PERMISSIONS, ROLE_PERMISSIONS.TENANT_ID, ROLE_PERMISSIONS.ROLE_ID, ROLE_PERMISSIONS.PERMISSION_CODE);
            for (String code : codes) {
                insert.values(role.tenantId().value(), role.id().value(), code);
            }
            insert.execute();
        }
    }

    static Role toRole(RolesRecord record, Set<String> permissions) {
        return Role.restore(
                new RoleId(record.getId()),
                new TenantId(record.getTenantId()),
                PermissionScope.valueOf(record.getScope()),
                record.getName(),
                record.getSystem(),
                permissions,
                record.getCreatedAt(),
                record.getVersion());
    }
}
