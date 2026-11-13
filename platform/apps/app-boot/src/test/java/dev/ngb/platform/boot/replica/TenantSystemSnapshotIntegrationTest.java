package dev.ngb.platform.boot.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemStatus;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The {@code tenant-systems} snapshot stream pages every system, carrying the version a replica guards on. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class TenantSystemSnapshotIntegrationTest {

    private static final Audit CALLER = Audit.of("user/admin", "test-1");

    @Autowired SnapshotController controller;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired ManageTenantSystemUseCase systems;

    @Test
    void aTenantSystemAppearsInTheSnapshotWithItsVersion() {
        UUID id = create();

        Map<String, Object> page = tenantContext.callAs(RequestScope.platform(), () -> controller.page("tenant-systems", null, 500));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        Map<String, Object> item = items.stream().filter(row -> row.get("id").equals(id)).findFirst().orElseThrow();
        assertThat(item.get("tenant_id")).isEqualTo(lastTenant.value());
        assertThat(item.get("version")).isEqualTo(1L);
        assertThat(item.get("status")).isEqualTo(TenantSystemStatus.ACTIVE);
        assertThat(item.get("created_at")).isNotNull();
    }

    private TenantId lastTenant;

    private UUID create() {
        String code = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TenantId tenantId = tenantContext.callAs(
                RequestScope.platform(),
                () -> createTenant.execute(new CreateTenantUseCase.Command(code, "Tenant " + code, "tester", "req-1")).id());
        lastTenant = tenantId;
        return tenantContext.callAs(
                RequestScope.tenant(tenantId),
                () -> systems.create(CALLER, "Sample " + UUID.randomUUID()).id().value());
    }
}
