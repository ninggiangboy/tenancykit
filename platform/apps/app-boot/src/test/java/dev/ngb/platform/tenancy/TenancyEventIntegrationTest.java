package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.DeactivateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.RenameTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** Every tenancy write publishes the aggregate's full new state with the next version, in the same transaction. */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class TenancyEventIntegrationTest {

    @Autowired CreateTenantUseCase createTenant;
    @Autowired RenameTenantUseCase renameTenant;
    @Autowired DeactivateTenantUseCase deactivateTenant;
    @Autowired SetAppEntitlementUseCase setApp;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void creatingRenamingAndDeactivatingPublishesTheNextVersionEachTime() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "acme" + suffix(), "Acme", "user/tester", "req-1")));

        assertThat(payloads("tenant.changed", tenant.id().value())).hasSize(1).allSatisfy(payload ->
                assertThat(payload.get("version").asLong()).isEqualTo(1));
        assertThat(payloads("tenant-domain.changed", tenant.id().value())).hasSize(1);

        onPlatform(() -> renameTenant.execute(new RenameTenantUseCase.Command(tenant.id(), "Acme Holdings", "user/tester", "req-2", ExpectedVersion.any())));
        assertThat(payloads("tenant.changed", tenant.id().value()))
                .hasSize(2)
                .last()
                .satisfies(payload -> {
                    assertThat(payload.get("version").asLong()).isEqualTo(2);
                    assertThat(payload.get("name").asString()).isEqualTo("Acme Holdings");
                });

        onPlatform(() -> deactivateTenant.execute(new DeactivateTenantUseCase.Command(tenant.id(), "user/tester", "req-3", ExpectedVersion.any())));
        assertThat(payloads("tenant.changed", tenant.id().value()))
                .hasSize(3)
                .last()
                .satisfies(payload -> {
                    assertThat(payload.get("version").asLong()).isEqualTo(3);
                    assertThat(payload.get("status").asString()).isEqualTo("DEACTIVATED");
                });

        // A second deactivation changes nothing and publishes nothing.
        onPlatform(() -> deactivateTenant.execute(new DeactivateTenantUseCase.Command(tenant.id(), "user/tester", "req-4", ExpectedVersion.any())));
        assertThat(payloads("tenant.changed", tenant.id().value())).hasSize(3);
    }

    @Test
    void enablingAnAppPublishesTheEntitlement() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "ent" + suffix(), "Entitled", "user/tester", "req-1")));

        onPlatform(() -> setApp.execute(new SetAppEntitlementUseCase.Command(tenant.id(), "billing", true, "user/tester", "req-2", ExpectedVersion.any())));

        List<tools.jackson.databind.JsonNode> published = payloads("entitlement.changed", tenant.id().value());
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().get("app_code").asString()).isEqualTo("billing");
        assertThat(published.getFirst().get("enabled").asBoolean()).isTrue();
        assertThat(published.getFirst().get("version").asLong()).isEqualTo(1);
    }

    private List<tools.jackson.databind.JsonNode> payloads(String type, java.util.UUID tenantId) {
        return owner.jdbc().query(
                "select payload::text from outbox where tenant_id = ? and type = ? order by created_at",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                tenantId,
                type);
    }

    private <T> T onPlatform(java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private static String suffix() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
