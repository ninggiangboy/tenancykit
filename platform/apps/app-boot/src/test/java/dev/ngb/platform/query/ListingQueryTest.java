package dev.ngb.platform.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.support.Actors;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every Platform row list answers the list-query contract: the body is {@code {data, page}}, filtering replaces the
 * removed {@code tenantId} parameter, the removed parameter is refused rather than ignored, and a system's credentials
 * are the only rows its credential page can hold. The caller is a Platform administrator, whose reads cover every
 * tenant; a Tenant-scope caller is narrowed by the read side and by row-level security, which the repository applies
 * as its scope.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PlatformContainerConfig.class, ListingQueryTest.FakeIdentities.class})
class ListingQueryTest {

    private static final String ADMIN = "platform:tenant:read platform:user:read platform:role:read "
            + "platform:tenant-system:read platform:credential:manage";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageTenantSystemUseCase systems;
    @Autowired ManageCredentialUseCase credentials;

    @Test
    void aTenantListIsAPageWithATotalAndACursorAndNeverThePlatformTenant() throws Exception {
        String first = newTenant().code().value();
        String second = newTenant().code().value();
        String filter = "/api/v1/tenants?filter[code][in]=" + first + "," + second + "&sort=code&limit=1";

        JsonNode page = getJson(filter + "&page_info=true");
        assertThat(page.get("data").size()).isEqualTo(1);
        assertThat(page.get("page").get("limit").asInt()).isEqualTo(1);
        assertThat(page.get("page").get("total").asLong()).as("the total counts both tenants, not the page").isEqualTo(2);
        assertThat(page.get("page").get("next_cursor").asString()).isNotBlank();

        JsonNode next = getJson(filter + "&page_info=true&cursor=" + page.get("page").get("next_cursor").asString());
        assertThat(next.get("data").size()).isEqualTo(1);
        assertThat(next.get("data").get(0).get("code").asString())
                .isNotEqualTo(page.get("data").get(0).get("code").asString());
        assertThat(next.get("page").get("next_cursor").isNull()).as("the last page has no next cursor").isTrue();

        assertThat(getJson("/api/v1/tenants?filter[code][eq]=platform&page_info=true").get("page").get("total").asLong())
                .as("the reserved platform tenant is the caller's own scope, never a listed tenant")
                .isZero();
    }

    @Test
    void usersAreFilteredByTenantAndTheRemovedParameterIsRefused() throws Exception {
        Tenant first = newTenant();
        Tenant second = newTenant();
        User mine = newUser(first);
        newUser(second);

        JsonNode page = getJson("/api/v1/users?filter[tenant_id][eq]=" + first.id().value() + "&limit=50");
        assertThat(page.get("data").size()).as("another tenant's user is never in the page").isEqualTo(1);
        assertThat(page.get("data").get(0).get("id").asString()).isEqualTo(mine.id().value().toString());
        assertThat(page.get("data").get(0).get("tenantId").asString()).isEqualTo(first.id().value().toString());
        assertThat(page.get("page").isNull()).as("page is null unless page_info=true").isTrue();

        assertThat(status("/api/v1/users?tenantId=" + first.id().value())).as("the removed tenantId parameter").isEqualTo(400);
        assertThat(status("/api/v1/users?filter[keycloak_subject][eq]=kc-x"))
                .as("the internal Keycloak subject is not a filterable field").isEqualTo(400);
        assertThat(status("/api/v1/users?sort=keycloak_subject")).as("nor is it sortable").isEqualTo(400);
        assertThat(status("/api/v1/users?limit=50")).isEqualTo(200);
    }

    @Test
    void aRoleListIsAPageAndAFilterOnTheOldParameterIsRefused() throws Exception {
        Tenant tenant = newTenant();
        String name = "Reader " + suffix();
        onPlatform(() -> createRole.execute(Actors.PLATFORM, "req-1", new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, name, Set.of("billing:invoice:read"))));

        JsonNode page = getJson("/api/v1/roles?filter[tenant_id][eq]=" + tenant.id().value()
                + "&filter[name][contains]=reader&sort=name&page_info=true");
        assertThat(page.get("data").size()).isEqualTo(1);
        assertThat(page.get("data").get(0).get("name").asString()).isEqualTo(name);
        assertThat(page.get("page").get("total").asLong()).isEqualTo(1);

        assertThat(status("/api/v1/roles?tenantId=" + tenant.id().value())).isEqualTo(400);
    }

    @Test
    void tenantSystemsAreAPageOfTheCallersTenant() throws Exception {
        Tenant tenant = newTenant();
        String name = "Billing " + suffix();
        onTenant(tenant, () -> systems.create(Audit.of("user/tester", "req-1"), name));

        JsonNode page = getJson("/api/v1/tenant-systems?filter[tenant_id][eq]=" + tenant.id().value()
                + "&filter[name][contains]=" + name + "&page_info=true");
        assertThat(page.get("data").size()).isEqualTo(1);
        assertThat(page.get("data").get(0).get("name").asString()).isEqualTo(name);
        assertThat(page.get("page").get("total").asLong()).isEqualTo(1);
    }

    @Test
    void aCredentialPageHoldsOnlyTheCredentialsOfTheSystemInThePath() throws Exception {
        Tenant tenant = newTenant();
        TenantSystem system = onTenant(tenant, () -> systems.create(Audit.of("user/tester", "req-1"), "System " + suffix()));
        TenantSystem other = onTenant(tenant, () -> systems.create(Audit.of("user/tester", "req-1"), "Other " + suffix()));
        onTenant(tenant, () -> credential(system, "Primary"));
        onTenant(tenant, () -> credential(system, "Backup"));
        onTenant(tenant, () -> credential(other, "Elsewhere"));

        JsonNode page = getJson("/api/v1/tenant-systems/" + system.id().value() + "/credentials?page_info=true&limit=50");
        assertThat(page.get("data").size()).as("another system's credential is never in the page").isEqualTo(2);
        assertThat(page.get("data").valueStream().map(row -> row.get("label").asString()))
                .containsExactly("Primary", "Backup");
        assertThat(page.get("page").get("total").asLong()).isEqualTo(2);
        assertThat(page.get("data").get(0).has("secretHash")).as("the listing never carries the digest").isFalse();
        assertThat(page.toString()).doesNotContain("secret");

        assertThat(status("/api/v1/tenant-systems/" + system.id().value()
                + "/credentials?filter[tenant_system_id][eq]=" + other.id().value()))
                .as("the path fixes the system, so it is not a filter").isEqualTo(400);
        assertThat(status("/api/v1/tenant-systems/" + other.id().value() + "/credentials?limit=50")).isEqualTo(200);
    }

    private ManageCredentialUseCase.Issued credential(TenantSystem system, String label) {
        return credentials.create(Audit.of("user/tester", "req-1"), system.id(), label, Set.of());
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Host", "localhost").header("Authorization", "Bearer " + adminToken())).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int status(String path) throws Exception {
        return mvc.perform(get(path).header("Host", "localhost").header("Authorization", "Bearer " + adminToken()))
                .andReturn().getResponse().getStatus();
    }

    private String adminToken() {
        return issuer.issueService("test-lister", Set.of(ADMIN.split(" ")), "platform").token();
    }

    private Tenant newTenant() {
        String code = "lq" + suffix();
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Listing " + code, "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(Actors.PLATFORM, "req-1", new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User " + email, "change-me")));
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
        IdentityProviderPort fakeIdentityProvider() {
            return new IdentityProviderPort() {
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
