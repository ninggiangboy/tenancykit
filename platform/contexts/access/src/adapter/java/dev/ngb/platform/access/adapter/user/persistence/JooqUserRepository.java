package dev.ngb.platform.access.adapter.user.persistence;

import static dev.ngb.platform_jooq.Tables.USERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import dev.ngb.platform.access.domain.user.UserStatus;
import dev.ngb.platform_jooq.tables.records.UsersRecord;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqUserRepository implements UserRepository {

    private final DSLContext dsl;

    public JooqUserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(User user) {
        dsl.insertInto(USERS)
                .set(USERS.ID, user.id().value())
                .set(USERS.TENANT_ID, user.tenantId().value())
                .set(USERS.SCOPE, user.scope().name())
                .set(USERS.KEYCLOAK_SUBJECT, user.keycloakSubject())
                .set(USERS.EMAIL, user.email())
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.STATUS, user.status().name())
                .set(USERS.CREATED_AT, user.createdAt())
                .set(USERS.VERSION, user.version())
                .execute();
    }

    @Override
    public void update(User user) {
        // The guard is defence in depth: a caller that did not hold the row lock cannot publish a duplicate version.
        int updated = dsl.update(USERS)
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.STATUS, user.status().name())
                .set(USERS.VERSION, user.version())
                .where(USERS.ID.eq(user.id().value()))
                .and(USERS.VERSION.lt(user.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("user " + user.id() + " was not updated: version " + user.version() + " is not newer");
        }
    }

    @Override
    public Optional<User> findById(UserId id) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(id.value())).fetchOptional(JooqUserRepository::toUser);
    }

    @Override
    public Optional<User> lockById(UserId id) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(id.value())).forUpdate().fetchOptional(JooqUserRepository::toUser);
    }

    @Override
    public Optional<User> findByKeycloakSubject(String subject) {
        return dsl.selectFrom(USERS).where(USERS.KEYCLOAK_SUBJECT.eq(subject)).fetchOptional(JooqUserRepository::toUser);
    }

    @Override
    public Optional<User> findByEmail(TenantId tenantId, String email) {
        return dsl.selectFrom(USERS)
                .where(USERS.TENANT_ID.eq(tenantId.value()))
                .and(USERS.EMAIL.eq(email))
                .fetchOptional(JooqUserRepository::toUser);
    }

    static User toUser(UsersRecord record) {
        return User.restore(
                new UserId(record.getId()),
                new TenantId(record.getTenantId()),
                PermissionScope.valueOf(record.getScope()),
                record.getKeycloakSubject(),
                record.getEmail(),
                record.getDisplayName(),
                UserStatus.valueOf(record.getStatus()),
                record.getCreatedAt(),
                record.getVersion());
    }
}
