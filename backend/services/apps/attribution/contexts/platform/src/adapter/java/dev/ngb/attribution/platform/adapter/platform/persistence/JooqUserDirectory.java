package dev.ngb.attribution.platform.adapter.platform.persistence;

import static dev.ngb.attribution_jooq.Tables.USER_GRANTS;

import dev.ngb.attribution.platform.api.replica.UserView;
import dev.ngb.attribution.platform.application.platform.port.UserRows;
import dev.ngb.core.id.TenantId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record8;

/** The user half of the replica read seam. Every query takes a tenant and filters on it. */
public class JooqUserDirectory implements UserRows {

    private final DSLContext dsl;

    public JooqUserDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<UserView> activeUsersOf(TenantId tenantId) {
        return fetch(USER_GRANTS.TENANT_ID.eq(tenantId.value()));
    }

    @Override
    public List<UserView> activeUsersWithPermission(TenantId tenantId, String code) {
        return fetch(USER_GRANTS.TENANT_ID.eq(tenantId.value())
                .and(USER_GRANTS.PERMISSIONS.contains(new String[] {code})));
    }

    @Override
    public Optional<UserView> user(UUID userId) {
        return dsl.select(
                        USER_GRANTS.USER_ID,
                        USER_GRANTS.TENANT_ID,
                        USER_GRANTS.SCOPE,
                        USER_GRANTS.EMAIL,
                        USER_GRANTS.DISPLAY_NAME,
                        USER_GRANTS.STATUS,
                        USER_GRANTS.PERMISSIONS,
                        USER_GRANTS.VERSION)
                .from(USER_GRANTS)
                .where(USER_GRANTS.USER_ID.eq(userId))
                .fetchOptional(JooqUserDirectory::view);
    }

    private List<UserView> fetch(Condition tenantAndStatus) {
        return dsl.select(
                        USER_GRANTS.USER_ID,
                        USER_GRANTS.TENANT_ID,
                        USER_GRANTS.SCOPE,
                        USER_GRANTS.EMAIL,
                        USER_GRANTS.DISPLAY_NAME,
                        USER_GRANTS.STATUS,
                        USER_GRANTS.PERMISSIONS,
                        USER_GRANTS.VERSION)
                .from(USER_GRANTS)
                .where(tenantAndStatus)
                .and(USER_GRANTS.SCOPE.eq("TENANT"))
                .and(USER_GRANTS.STATUS.eq("ACTIVE"))
                .fetch(JooqUserDirectory::view);
    }

    private static UserView view(Record8<UUID, UUID, String, String, String, String, String[], Long> row) {
        Set<String> permissions = row.value7() == null
                ? Set.of()
                : Arrays.stream(row.value7()).collect(Collectors.toUnmodifiableSet());
        return new UserView(
                row.value1(),
                new TenantId(row.value2()),
                row.value3(),
                row.value4(),
                row.value5(),
                row.value6(),
                permissions,
                row.value8());
    }
}
