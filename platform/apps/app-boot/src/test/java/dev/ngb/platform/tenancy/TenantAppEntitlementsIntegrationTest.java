package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.Application;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read the tenants screen needs: every app of the suite with the tenant's state, an app the tenant never changed
 * being disabled at version 0 — exactly the absent-row case a conditional update expects.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class TenantAppEntitlementsIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired SetAppEntitlementUseCase setApp;

    @Test
    void everyAppOfTheSuiteIsListedAndAnUnchangedOneIsDisabledAtZero() throws Exception {
        Tenant tenant = newTenant();

        JsonNode apps = apps(tenant, "platform:tenant:read");

        assertThat(apps).isNotEmpty();
        assertThat(codes(apps)).contains("billing", "platform", "survey", "workflow");
        for (JsonNode app : apps) {
            assertThat(app.get("enabled").asBoolean()).isFalse();
            assertThat(app.get("version").asLong()).isZero();
        }
    }

    @Test
    void anEnabledAppIsListedWithItsVersion() throws Exception {
        Tenant tenant = newTenant();
        tenantContext.callAs(RequestScope.platform(), () -> setApp.execute(new SetAppEntitlementUseCase.Command(
                tenant.id(), "billing", true, "user/tester", "req-1", ExpectedVersion.any())));

        JsonNode apps = apps(tenant, "platform:tenant:read");
        JsonNode billing = null;
        for (JsonNode app : apps) {
            if ("billing".equals(app.get("appCode").asString())) {
                billing = app;
            }
        }

        assertThat(billing).isNotNull();
        assertThat(billing.get("enabled").asBoolean()).isTrue();
        assertThat(billing.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void anUnknownTenantIs404AndACallerWithoutThePermissionIs403() throws Exception {
        MvcResult unknown = perform(UUID.randomUUID(), "platform:tenant:read");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);

        Tenant tenant = newTenant();
        assertThat(perform(tenant.id().value(), "platform:ui-remote:read").getResponse().getStatus()).isEqualTo(403);
    }

    private JsonNode apps(Tenant tenant, String... scopes) throws Exception {
        MvcResult result = perform(tenant.id().value(), scopes);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult perform(UUID tenantId, String... scopes) throws Exception {
        return mvc.perform(get("/api/v1/tenants/" + tenantId + "/apps")
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + issuer.issueService("reader", Set.of(scopes), "platform").token()))
                .andReturn();
    }

    private static List<String> codes(JsonNode apps) {
        List<String> codes = new java.util.ArrayList<>();
        apps.forEach(app -> codes.add(app.get("appCode").asString()));
        return codes;
    }

    private Tenant newTenant() {
        String code = "app" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return tenantContext.callAs(RequestScope.platform(), () -> createTenant.execute(
                new CreateTenantUseCase.Command(code, "Apps " + code, "user/tester", "req-1")));
    }
}
