package dev.ngb.platform.boot.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A page of the {@code entitlements} stream never splits one tenant's rows, so following the cursor drops nothing. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class EntitlementSnapshotPagingIntegrationTest {

    @Autowired SnapshotController controller;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired SetAppEntitlementUseCase setApp;

    @Test
    @SuppressWarnings("unchecked")
    void pagingBySmallPagesReturnsEveryAppOfEveryTenant() {
        Set<String> expected = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            String code = "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            TenantId tenantId = tenantContext.callAs(
                    RequestScope.platform(),
                    () -> createTenant.execute(new CreateTenantUseCase.Command(code, "Tenant " + code, "tester", "req-1")).id());
            for (String app : List.of("billing", "platform")) {
                tenantContext.callAs(RequestScope.platform(), () -> setApp.execute(
                        new SetAppEntitlementUseCase.Command(tenantId, app, true, "tester", "req-2", ExpectedVersion.any())));
                expected.add(tenantId.value() + "/" + app);
            }
        }

        Set<String> seen = new java.util.HashSet<>();
        UUID after = null;
        List<Integer> pageSizes = new ArrayList<>();
        do {
            UUID cursor = after;
            Map<String, Object> page = tenantContext.callAs(RequestScope.platform(), () -> controller.page("entitlements", cursor, 1));
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            pageSizes.add(items.size());
            items.forEach(item -> seen.add(item.get("tenant_id") + "/" + item.get("app_code")));
            after = (UUID) page.get("next");
        } while (after != null);

        assertThat(seen).containsAll(expected);
        assertThat(pageSizes).allMatch(size -> size >= 2 || size == 0);
    }
}
