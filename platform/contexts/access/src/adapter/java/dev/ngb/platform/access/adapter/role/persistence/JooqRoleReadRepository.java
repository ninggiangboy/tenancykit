package dev.ngb.platform.access.adapter.role.persistence;

import static dev.ngb.platform_jooq.Tables.ROLES;
import static dev.ngb.platform_jooq.Tables.ROLE_PERMISSIONS;

import dev.ngb.platform.access.application.role.query.RoleReadRepository;
import dev.ngb.platform.access.application.role.query.RoleView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;

public class JooqRoleReadRepository implements RoleReadRepository {

    private final DSLContext dsl;

    public JooqRoleReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<RoleView> list(UUID tenantId) {
        return dsl.selectFrom(ROLES).where(ROLES.TENANT_ID.eq(tenantId)).orderBy(ROLES.NAME)
                .fetch(record -> toView(record.getId(), record.getTenantId(), record.getScope(), record.getName(),
                        record.getSystem(), record.getVersion()));
    }

    @Override
    public Optional<RoleView> findById(UUID id) {
        return dsl.selectFrom(ROLES).where(ROLES.ID.eq(id))
                .fetchOptional(record -> toView(record.getId(), record.getTenantId(), record.getScope(), record.getName(),
                        record.getSystem(), record.getVersion()));
    }

    private RoleView toView(UUID id, UUID tenantId, String scope, String name, boolean system, long version) {
        Set<String> permissions = dsl.select(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(id))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE)
                .stream()
                .collect(Collectors.toUnmodifiableSet());
        return new RoleView(id, tenantId, scope, name, system, permissions, version);
    }
}
