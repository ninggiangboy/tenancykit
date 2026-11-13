package dev.ngb.platform.web;
import dev.ngb.platform.support.Actors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.support.OwnerDatabase;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every in-scope Platform update answers an {@code ETag} and honours an optional {@code If-Match}: absent is
 * last-write-wins, current is applied, stale is 412 with the current tag and no write, {@code *} passes, a weak tag
 * never matches, and a malformed header is a 400.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PlatformContainerConfig.class, ConditionalUpdateHttpIntegrationTest.FakeIdentities.class})
class ConditionalUpdateHttpIntegrationTest {

    private static final String ADMIN = "platform:tenant:create platform:tenant:rename platform:tenant:deactivate platform:tenant:read "
            + "platform:role:manage platform:user:manage platform:tenant-system:manage platform:tenant-system:read "
            + "platform:entitlement:manage";

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateRoleUseCase createRole;
    @Autowired CreateUserUseCase createUser;
    @Autowired ManageTenantSystemUseCase systems;
    @Autowired OwnerDatabase owner;
    @Value("${platform.tenancy.base-domain}") String baseDomain;

    @Test
    void tenantRenameFollowsTheConditionalMatrix() throws Exception {
        Tenant tenant = newTenant();
        assertMatrix(platformHost(), "/api/v1/tenants/" + tenant.id().value(), "PUT", "{\"name\":\"Renamed\"}", tenant.version());
    }

    @Test
    void tenantDeactivateFollowsTheConditionalMatrix() throws Exception {
        Tenant tenant = newTenant();
        assertMatrix(platformHost(), "/api/v1/tenants/" + tenant.id().value() + "/deactivate", "POST", null, tenant.version());
    }

    @Test
    void roleRenameAndReplacePermissionsFollowTheConditionalMatrix() throws Exception {
        Tenant tenant = newTenant();
        Role role = onPlatform(() -> createRole.execute(Actors.PLATFORM, "req-1",
                new CreateRoleUseCase.Command(tenant.id(), PermissionScope.TENANT, "Reader-" + suffix(), Set.of("billing:invoice:read"))));

        assertMatrix(platformHost(), "/api/v1/roles/" + role.id().value(), "PUT", "{\"name\":\"Renamed\"}", role.version());
        Role reloaded = onPlatform(() -> createRole.execute(Actors.PLATFORM, "req-1", new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, "Second-" + suffix(), Set.of("billing:invoice:read"))));
        assertMatrix(platformHost(), "/api/v1/roles/" + reloaded.id().value() + "/permissions", "PUT",
                "{\"permissions\":[\"billing:invoice:read\",\"billing:wallet:read\"]}", reloaded.version());
    }

    @Test
    void userPatchAndDirectPermissionFollowTheConditionalMatrix() throws Exception {
        Tenant tenant = newTenant();
        User first = newUser(tenant);
        User second = newUser(tenant);

        assertMatrix(platformHost(), "/api/v1/users/" + first.id().value(), "PATCH",
                "{\"action\":\"RENAME\",\"name\":\"Renamed\"}", first.version());
        assertMatrix(platformHost(), "/api/v1/users/" + second.id().value() + "/permissions/billing:wallet:read?grant=true",
                "PUT", null, second.version());
    }

    @Test
    void tenantSystemUpdateAndGetCarryTheTag() throws Exception {
        Tenant tenant = newTenant();
        TenantSystem system = onTenant(tenant, () -> systems.create(Audit.of("user/tester", "req"), "System " + suffix()));
        String host = tenant.code().value() + "." + baseDomain;

        MvcResult got = mvc.perform(get("/api/v1/tenant-systems/" + system.id())
                        .header("Host", host).header("Authorization", "Bearer " + adminToken()))
                .andReturn();
        assertThat(got.getResponse().getStatus()).isEqualTo(200);
        assertThat(got.getResponse().getHeader("ETag")).isEqualTo("\"" + system.version() + "\"");

        assertMatrix(host, "/api/v1/tenant-systems/" + system.id(), "PUT",
                "{\"action\":\"RENAME\",\"name\":\"Renamed\"}", system.version());
    }

    @Test
    void entitlementSetFollowsTheConditionalMatrixAndAnAbsentRowRejectsATag() throws Exception {
        Tenant tenant = newTenant();
        MvcResult created = mvc.perform(update(platformHost(), "/api/v1/tenants/" + tenant.id().value() + "/apps/billing",
                        "PUT", "{\"enabled\":true}", null))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

        assertMatrix(platformHost(), "/api/v1/tenants/" + tenant.id().value() + "/apps/billing", "PUT",
                "{\"enabled\":false}", 1);

        // A tag on an entitlement that does not exist yet has nothing to compare against.
        Tenant other = newTenant();
        MvcResult absent = mvc.perform(update(platformHost(), "/api/v1/tenants/" + other.id().value() + "/apps/billing",
                        "PUT", "{\"enabled\":true}", "\"3\""))
                .andReturn();
        assertThat(absent.getResponse().getStatus()).isEqualTo(412);
    }

    /**
     * The cases every endpoint shares. The rejections run first, while the row still holds {@code currentVersion}, so
     * the later successes can move it without making the matrix's own expectations stale.
     */
    private void assertMatrix(String host, String path, String method, String body, long currentVersion) throws Exception {
        long outboxBefore = owner.count("select count(*) from outbox");
        long auditBefore = owner.count("select count(*) from audit_log");

        MvcResult stale = perform(host, method, path, body, "\"" + (currentVersion + 1_000) + "\"");
        assertThat(stale.getResponse().getStatus()).as("%s %s with a stale tag", method, path).isEqualTo(412);
        assertThat(stale.getResponse().getHeader("ETag"))
                .as("412 carries the current tag so the caller can retry without a GET")
                .isEqualTo("\"" + currentVersion + "\"");
        assertThat(owner.count("select count(*) from outbox")).as("a rejected write publishes nothing").isEqualTo(outboxBefore);
        assertThat(owner.count("select count(*) from audit_log")).as("a rejected write audits nothing").isEqualTo(auditBefore);

        MvcResult weak = perform(host, method, path, body, "W/\"" + currentVersion + "\"");
        assertThat(weak.getResponse().getStatus()).as("%s %s with a weak tag", method, path).isEqualTo(412);

        MvcResult malformed = perform(host, method, path, body, "3");
        assertThat(malformed.getResponse().getStatus()).as("%s %s with a malformed tag", method, path).isEqualTo(400);

        MvcResult current = perform(host, method, path, body, "\"" + currentVersion + "\"");
        assertThat(current.getResponse().getStatus()).as("%s %s with the current tag", method, path).isEqualTo(200);
        assertThat(current.getResponse().getHeader("ETag")).isNotNull();

        MvcResult star = perform(host, method, path, body, "*");
        assertThat(star.getResponse().getStatus()).as("%s %s with *", method, path).isEqualTo(200);

        MvcResult absent = perform(host, method, path, body, null);
        assertThat(absent.getResponse().getStatus()).as("%s %s without If-Match", method, path).isEqualTo(200);
        assertThat(absent.getResponse().getHeader("ETag")).isNotNull();
    }

    private MvcResult perform(String host, String method, String path, String body, String ifMatch) throws Exception {
        return mvc.perform(update(host, path, method, body, ifMatch)).andReturn();
    }

    private MockHttpServletRequestBuilder update(String host, String path, String method, String body, String ifMatch) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "PUT" -> put(path);
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            default -> throw new IllegalArgumentException(method);
        };
        request.header("Host", host).header("Authorization", "Bearer " + adminToken());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return request;
    }

    private String adminToken() {
        return issuer.issueService("test-admin", Set.of(ADMIN.split(" ")), "platform").token();
    }

    private static String platformHost() {
        return "localhost";
    }

    private Tenant newTenant() {
        String code = "cu" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Conditional " + code, "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(Actors.PLATFORM, "req-1", new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me")));
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private <T> T onTenant(Tenant tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant.id()), work);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentities {

        @Bean
        @Primary
        dev.ngb.platform.access.application.auth.port.IdentityProviderPort fakeIdentityProvider() {
            return new dev.ngb.platform.access.application.auth.port.IdentityProviderPort() {
                @Override
                public String createUser(String username, String email, String displayName, String temporaryPassword) {
                    return "kc-" + username;
                }

                @Override
                public void deleteUser(String keycloakSubject) {}
            };
        }
    }
}
