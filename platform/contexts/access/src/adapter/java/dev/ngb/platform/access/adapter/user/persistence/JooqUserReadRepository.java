package dev.ngb.platform.access.adapter.user.persistence;

import static dev.ngb.platform_jooq.Tables.ROLE_ASSIGNMENTS;
import static dev.ngb.platform_jooq.Tables.ROLE_PERMISSIONS;
import static dev.ngb.platform_jooq.Tables.USERS;
import static dev.ngb.platform_jooq.Tables.USER_PERMISSIONS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.access.api.user.UserAccessView;
import dev.ngb.platform.access.api.user.UserReadRepository;
import dev.ngb.platform.access.api.user.UserView;
import dev.ngb.platform.access.application.user.query.UserListing;
import dev.ngb.platform_jooq.tables.records.UsersRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The read side of users and their effective access, including the user-access replica stream. */
public class JooqUserReadRepository implements UserReadRepository {

    /** The fields a caller may filter and sort users on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(UserListing.SCHEMA)
            .column("id", USERS.ID)
            .column("tenant_id", USERS.TENANT_ID)
            .column("scope", USERS.SCOPE)
            .column("email", USERS.EMAIL)
            .column("display_name", USERS.DISPLAY_NAME)
            .column("status", USERS.STATUS)
            .column("created_at", USERS.CREATED_AT)
            .column("version", USERS.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the user listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqUserReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The table is row-level secured, so the scope is belt and braces: it makes the Tenant a Tenant-scope caller may
     * list explicit, and a filter on another Tenant can then only narrow the page to no row.
     */
    @Override
    public Page<UserView> list(ListQuery query, UUID tenantScope) {
        Condition scope = tenantScope == null ? DSL.noCondition() : USERS.TENANT_ID.eq(tenantScope);
        return JooqLists.fetch(dsl, USERS, scope, BINDING, query, JooqUserReadRepository::toView);
    }

    @Override
    public Optional<UserView> findById(UUID id) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOptional(JooqUserReadRepository::toView);
    }

    @Override
    public Optional<UserView> findByKeycloakSubject(String subject) {
        return dsl.selectFrom(USERS).where(USERS.KEYCLOAK_SUBJECT.eq(subject)).fetchOptional(JooqUserReadRepository::toView);
    }

    @Override
    public Set<String> effectivePermissions(UUID userId) {
        Set<String> direct = new HashSet<>(dsl.select(USER_PERMISSIONS.PERMISSION_CODE)
                .from(USER_PERMISSIONS)
                .where(USER_PERMISSIONS.USER_ID.eq(userId))
                .fetch(USER_PERMISSIONS.PERMISSION_CODE));
        Set<String> fromRoles = new HashSet<>(dsl.selectDistinct(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .join(ROLE_ASSIGNMENTS).on(ROLE_ASSIGNMENTS.ROLE_ID.eq(ROLE_PERMISSIONS.ROLE_ID))
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
        Set<String> all = new HashSet<>(direct);
        all.addAll(fromRoles);
        return all;
    }

    @Override
    public List<UserAccessView> pageAccess(UUID after, int limit) {
        return dsl.selectFrom(USERS)
                .where(after == null ? DSL.noCondition() : USERS.ID.gt(after))
                .orderBy(USERS.ID)
                .limit(limit)
                .fetch(record -> new UserAccessView(toView(record), effectivePermissions(record.getId())));
    }

    private static UserView toView(UsersRecord record) {
        return new UserView(
                record.getId(), record.getTenantId(), record.getScope(), record.getKeycloakSubject(), record.getEmail(),
                record.getDisplayName(), record.getStatus(), record.getVersion());
    }
}
