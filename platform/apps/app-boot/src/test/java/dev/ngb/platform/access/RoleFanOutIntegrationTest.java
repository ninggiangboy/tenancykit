package dev.ngb.platform.access;
import dev.ngb.platform.support.Actors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Editing a role held by 1,500 users republishes every holder's access in the one transaction of the edit. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class RoleFanOutIntegrationTest {

    private static final int HOLDERS = 1_500;

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageRoleUseCase manageRole;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void aRoleWithManyHoldersRepublishesEveryHolderOnce() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "fan" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), "Fan out", "user/tester", "req-1")));
        Role role = onPlatform(() -> createRole.execute(Actors.PLATFORM, new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, "Reader", Set.of("billing:invoice:read"))));
        UUID tenantId = tenant.id().value();
        owner.jdbc().update("""
                insert into users (id, tenant_id, scope, keycloak_subject, email, display_name, status, created_at, version)
                select gen_random_uuid(), ?, 'TENANT', 'kc-' || n || '-' || ?, 'u' || n || '@fan.test', 'User ' || n, 'ACTIVE', now(), 1
                from generate_series(1, ?) n""", tenantId, tenantId.toString(), HOLDERS);
        owner.jdbc().update(
                "insert into role_assignments (tenant_id, user_id, role_id) select tenant_id, id, ? from users where tenant_id = ?",
                role.id().value(), tenantId);

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.REPLACE_PERMISSIONS,
                Set.of("billing:invoice:read", "billing:wallet:read"), null, null, ExpectedVersion.any())));

        assertThat(owner.count(
                        "select count(distinct payload ->> 'user_id') from outbox where type = 'user-access.changed' and tenant_id = ?",
                        tenantId))
                .isEqualTo(HOLDERS);
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }
}
