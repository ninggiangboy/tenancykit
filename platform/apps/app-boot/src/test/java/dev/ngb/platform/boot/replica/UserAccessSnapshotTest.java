package dev.ngb.platform.boot.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The user-access replica stream carries each user's effective permissions, which is all a consumer stores. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class UserAccessSnapshotTest {

    private static final String PLATFORM_TENANT = "00000000-0000-7000-8000-000000000001";

    @Autowired SnapshotController controller;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void aUserIsPagedWithTheCodesOfItsRole() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String subject = "kc-" + userId;
        Timestamp now = Timestamp.from(Instant.now());
        owner.jdbc().update(
                "insert into users (id, tenant_id, scope, keycloak_subject, email, display_name, status, created_at, version)"
                        + " values (?, ?, 'PLATFORM', ?, ?, 'Snapshot User', 'ACTIVE', ?, 1)",
                userId, UUID.fromString(PLATFORM_TENANT), subject, subject + "@example.test", now);
        owner.jdbc().update(
                "insert into roles (id, tenant_id, scope, name, system, created_at, version) values (?, ?, 'PLATFORM', ?, false, ?, 1)",
                roleId, UUID.fromString(PLATFORM_TENANT), "Snapshot Role " + roleId, now);
        owner.jdbc().update(
                "insert into role_permissions (tenant_id, role_id, permission_code) values (?, ?, 'platform:tenant:read')",
                UUID.fromString(PLATFORM_TENANT), roleId);
        owner.jdbc().update(
                "insert into role_assignments (tenant_id, user_id, role_id) values (?, ?, ?)",
                UUID.fromString(PLATFORM_TENANT), userId, roleId);

        List<Map<String, Object>> items = allPages("user-access");

        assertThat(items).filteredOn(item -> userId.equals(item.get("user_id"))).singleElement().satisfies(item -> {
            assertThat(item.get("keycloak_subject")).isEqualTo(subject);
            assertThat(item.get("scope")).isEqualTo("PLATFORM");
            assertThat(((List<?>) item.get("permissions")).stream().map(String::valueOf).toList())
                    .containsExactly("platform:tenant:read");
            assertThat((Long) item.get("version")).isEqualTo(1L);
        });
    }

    /** Follows the cursor to the end, because other tests leave any number of users in the shared database. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allPages(String stream) {
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        UUID after = null;
        do {
            UUID cursor = after;
            Map<String, Object> page = onPlatform(() -> controller.page(stream, cursor, 500));
            all.addAll((List<Map<String, Object>>) page.get("items"));
            after = (UUID) page.get("next");
        } while (after != null);
        return all;
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }
}
