package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who may touch the registry: CI registers and holds nothing else, an operator manages and reads, and a Tenant System
 * cannot register even if a token were minted carrying the code, because the catalog marks registration platform-only.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class UiRemoteAccessIntegrationTest {

    private static final String INTEGRITY = "sha384-" + "A".repeat(64);
    private static final Audit CALLER = Audit.of("user/admin", "req-1");

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired ManageTenantSystemUseCase systems;
    @Value("${platform.tenancy.base-domain}") String baseDomain;

    @Test
    void theFrontendCiClientRegistersButCannotManageOrRead() throws Exception {
        String name = newName();

        MvcResult registered = mvc.perform(post("/api/v1/ui-remotes/" + name + "/versions")
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + token("frontend-ci", "platform:ui-remote:register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name)))
                .andReturn();

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);

        MvcResult manage = mvc.perform(put("/api/v1/ui-remotes/" + name)
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + token("frontend-ci", "platform:ui-remote:register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"activeVersion\":\"1.0.0\"}"))
                .andReturn();
        assertThat(manage.getResponse().getStatus()).isEqualTo(403);

        MvcResult read = mvc.perform(get("/api/v1/ui-remotes")
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + token("frontend-ci", "platform:ui-remote:register")))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anOperatorTokenReadsAndManagesButCannotRegister() throws Exception {
        String name = newName();
        String operator = token("operator", "platform:ui-remote:read", "platform:ui-remote:manage");

        assertThat(mvc.perform(get("/api/v1/ui-remotes").header("Host", "localhost").header("Authorization", "Bearer " + operator))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);

        MvcResult register = mvc.perform(post("/api/v1/ui-remotes/" + name + "/versions")
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name)))
                .andReturn();
        assertThat(register.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aTenantSystemCannotRegisterEvenWithTheCodeInItsToken() throws Exception {
        Tenant tenant = newTenant();
        TenantSystem system = onTenant(tenant, () -> systems.create(CALLER, "Console " + UUID.randomUUID()));
        String tenantToken = issuer
                .issueTenantSystem(
                        system.id(), tenant.id(), tenant.code().value(), Set.of("platform:ui-remote:register"), "platform")
                .token();
        String name = newName();

        MvcResult result = mvc.perform(post("/api/v1/ui-remotes/" + name + "/versions")
                        .header("Host", tenant.code().value() + "." + baseDomain)
                        .header("Authorization", "Bearer " + tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anUnauthenticatedCallerIs401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/ui-remotes").header("Host", "localhost"))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    private String token(String client, String... scopes) {
        return issuer.issueService(client, Set.of(scopes), "platform").token();
    }

    private static String registerBody(String name) {
        return """
                {"version":"1.0.0","entryPath":"/console/remotes/%s/1.0.0/mf-manifest.json","integrity":"%s",
                 "sdkMajor":1,"appCode":"billing","title":"%s","audiences":["TENANT"],"navOrder":10}"""
                .formatted(name, INTEGRITY, name);
    }

    private Tenant newTenant() {
        String code = "acc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return tenantContext.callAs(RequestScope.platform(), () -> createTenant.execute(
                new CreateTenantUseCase.Command(code, "Access " + code, "user/tester", "req-1")));
    }

    private <T> T onTenant(Tenant tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant.id()), work);
    }

    private static String newName() {
        return "ui" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
