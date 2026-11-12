package dev.ngb.platform.access.adapter.access.persistence;

import static dev.ngb.platform_jooq.Tables.ROLE_ASSIGNMENTS;
import static dev.ngb.platform_jooq.Tables.ROLE_PERMISSIONS;
import static dev.ngb.platform_jooq.Tables.USER_PERMISSIONS;

import dev.ngb.platform.access.application.access.port.AccessRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

/** The joins behind effective access, resolved in the database so Platform never caches a permission set. */
public class JooqAccessRepository implements AccessRepository {

    private final DSLContext dsl;

    public JooqAccessRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Set<String> effectivePermissions(UUID userId) {
        Set<String> direct = new HashSet<>(dsl.select(USER_PERMISSIONS.PERMISSION_CODE)
                .from(USER_PERMISSIONS)
                .where(USER_PERMISSIONS.USER_ID.eq(userId))
                .fetch(USER_PERMISSIONS.PERMISSION_CODE));
        Set<String> fromRoles = new HashSet<>(dsl.selectDistinct(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .join(ROLE_ASSIGNMENTS)
                .on(ROLE_ASSIGNMENTS.ROLE_ID.eq(ROLE_PERMISSIONS.ROLE_ID))
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
        Set<String> all = new HashSet<>(direct);
        all.addAll(fromRoles);
        return all;
    }

    @Override
    public List<UUID> userIdsForRole(UUID roleId) {
        return dsl.select(ROLE_ASSIGNMENTS.USER_ID)
                .from(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.ROLE_ID.eq(roleId))
                .orderBy(ROLE_ASSIGNMENTS.USER_ID)
                .fetch(ROLE_ASSIGNMENTS.USER_ID);
    }

    @Override
    public void assign(UUID tenantId, UUID userId, UUID roleId) {
        dsl.insertInto(ROLE_ASSIGNMENTS)
                .set(ROLE_ASSIGNMENTS.TENANT_ID, tenantId)
                .set(ROLE_ASSIGNMENTS.USER_ID, userId)
                .set(ROLE_ASSIGNMENTS.ROLE_ID, roleId)
                .onConflict(ROLE_ASSIGNMENTS.USER_ID, ROLE_ASSIGNMENTS.ROLE_ID)
                .doNothing()
                .execute();
    }

    @Override
    public void unassign(UUID tenantId, UUID userId, UUID roleId) {
        dsl.deleteFrom(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .and(ROLE_ASSIGNMENTS.ROLE_ID.eq(roleId))
                .execute();
    }

    @Override
    public void grant(UUID tenantId, UUID userId, String permissionCode) {
        dsl.insertInto(USER_PERMISSIONS)
                .set(USER_PERMISSIONS.TENANT_ID, tenantId)
                .set(USER_PERMISSIONS.USER_ID, userId)
                .set(USER_PERMISSIONS.PERMISSION_CODE, permissionCode)
                .onConflict(USER_PERMISSIONS.USER_ID, USER_PERMISSIONS.PERMISSION_CODE)
                .doNothing()
                .execute();
    }

    @Override
    public void revoke(UUID tenantId, UUID userId, String permissionCode) {
        dsl.deleteFrom(USER_PERMISSIONS)
                .where(USER_PERMISSIONS.USER_ID.eq(userId))
                .and(USER_PERMISSIONS.PERMISSION_CODE.eq(permissionCode))
                .execute();
    }

    @Override
    public Set<String> directPermissions(UUID userId) {
        return new HashSet<>(dsl.select(USER_PERMISSIONS.PERMISSION_CODE)
                .from(USER_PERMISSIONS)
                .where(USER_PERMISSIONS.USER_ID.eq(userId))
                .fetch(USER_PERMISSIONS.PERMISSION_CODE));
    }
}
