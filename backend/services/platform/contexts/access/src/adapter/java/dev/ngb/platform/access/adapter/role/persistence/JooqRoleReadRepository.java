package dev.ngb.platform.access.adapter.role.persistence;

import static dev.ngb.platform_jooq.Tables.ROLES;
import static dev.ngb.platform_jooq.Tables.ROLE_PERMISSIONS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.access.application.role.query.RoleListing;
import dev.ngb.platform.access.application.role.query.RoleReadRepository;
import dev.ngb.platform.access.application.role.query.RoleView;
import dev.ngb.platform_jooq.tables.records.RolesRecord;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class JooqRoleReadRepository implements RoleReadRepository {

    /** The fields a caller may filter and sort roles on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(RoleListing.SCHEMA)
            .column("id", ROLES.ID)
            .column("tenant_id", ROLES.TENANT_ID)
            .column("scope", ROLES.SCOPE)
            .column("name", ROLES.NAME)
            .column("system", ROLES.SYSTEM)
            .column("created_at", ROLES.CREATED_AT)
            .column("version", ROLES.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the role listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqRoleReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The table is row-level secured, so the scope is belt and braces: it makes the Tenant a Tenant-scope caller may
     * list explicit, and a filter on another Tenant can then only narrow the page to no row.
     */
    @Override
    public Page<RoleView> list(ListQuery query, UUID tenantScope) {
        Condition scope = tenantScope == null ? DSL.noCondition() : ROLES.TENANT_ID.eq(tenantScope);
        return JooqLists.fetch(dsl, ROLES, scope, BINDING, query, record -> toView(record));
    }

    @Override
    public Optional<RoleView> findById(UUID id) {
        return dsl.selectFrom(ROLES).where(ROLES.ID.eq(id))
                .fetchOptional(record -> toView(record.getId(), record.getTenantId(), record.getScope(), record.getName(),
                        record.getSystem(), record.getVersion()));
    }

    private RoleView toView(RolesRecord record) {
        return toView(record.getId(), record.getTenantId(), record.getScope(), record.getName(), record.getSystem(),
                record.getVersion());
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
