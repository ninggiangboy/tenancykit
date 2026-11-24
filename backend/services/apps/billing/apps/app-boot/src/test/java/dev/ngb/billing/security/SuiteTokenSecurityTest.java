package dev.ngb.billing.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.support.HttpWorld;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ReplicaFixtures;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.billing.support.TestTokensConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Billing accepts exactly two issuers and the {@code billing} audience, and no API key: a Tenant System exchanges
 * its credential at Platform for a suite token. An old {@code bk_}/{@code cbk_} bearer is nobody.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TestTokensConfig.class})
class SuiteTokenSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired ScopeTestSupport scopes;
    @Autowired ReplicaFixtures replica;

    private HttpWorld world() {
        return new HttpWorld(mvc, replica, scopes);
    }

    @Test
    void anOldApiKeyInTheAuthorizationHeaderIs401() {
        HttpWorld world = world();
        HttpWorld.Account account = world.newTenant();

        assertThat(world.status(HttpMethod.GET, account.host(), "cbk_0123456789abcdef_" + "0".repeat(64), "/api/v1/customers", null))
                .as("a Billing/Tenant System API key is no longer a credential Billing understands")
                .isEqualTo(401);
        assertThat(world.status(HttpMethod.GET, account.host(), "bk_0123456789abcdef", "/api/v1/customers", null))
                .as("an old Billing key prefix is refused")
                .isEqualTo(401);
    }

    @Test
    void aTokenMintedForAnotherAudienceIs401() {
        HttpWorld world = world();
        HttpWorld.Account account = world.newTenant();
        String subject = "kc-" + java.util.UUID.randomUUID();
        replica.user(account.id().value(), subject, "billing:customer:read");
        String foreign = dev.ngb.billing.support.TestTokenFactory.tokenForOtherAudience(subject);

        assertThat(world.status(HttpMethod.GET, account.host(), foreign, "/api/v1/customers", null))
                .as("a token minted for another audience is not a billing token")
                .isEqualTo(401);
    }

    @Test
    void aTenantSystemTokenForAnotherTenantIs403() {
        HttpWorld world = world();
        HttpWorld.Account acme = world.newTenant();
        HttpWorld.Account globex = world.newTenant();
        // A token whose tenant is globex, sent to the acme host.
        String systemId = replica.tenantSystem(globex.id().value(), "Foreign " + java.util.UUID.randomUUID()).toString();
        String token = dev.ngb.billing.support.TestTokenFactory.tenantSystemToken(
                java.util.UUID.fromString(systemId), globex.id().value(), "billing:customer:read");

        assertThat(world.status(HttpMethod.GET, acme.host(), token, "/system/v1/customers", null))
                .as("the tenant of the token must equal the host's tenant")
                .isEqualTo(403);
    }

    @Test
    void eachDoorAcceptsOnlyItsOwnKindOfToken() throws Exception {
        HttpWorld world = world();
        HttpWorld.Account account = world.newTenant();
        HttpWorld.System system = world.newSystem(account, "billing:customer:read");
        String person = world.newUserToken(account, "billing:customer:read");

        assertThat(world.status(HttpMethod.GET, account.host(), person, "/api/v1/customers", null)).isEqualTo(200);
        assertThat(world.status(HttpMethod.GET, account.host(), system.token(), "/system/v1/customers", null)).isEqualTo(200);

        var machineAtPersonDoor = world.call(HttpMethod.GET, account.host(), system.token(), "/api/v1/customers", null);
        assertThat(machineAtPersonDoor.getResponse().getStatus())
                .as("a genuine suite token at the person door").isEqualTo(403);
        assertThat(machineAtPersonDoor.getResponse().getContentAsString()).contains("wrong-token-kind");
        var personAtMachineDoor = world.call(HttpMethod.GET, account.host(), person, "/system/v1/customers", null);
        assertThat(personAtMachineDoor.getResponse().getStatus())
                .as("a genuine user token at the machine door").isEqualTo(403);
        assertThat(personAtMachineDoor.getResponse().getContentAsString()).contains("wrong-token-kind");
    }

    @Test
    void anUnknownHostIs404() {
        HttpWorld world = world();
        assertThat(world.status(HttpMethod.GET, "nobody.localhost", null, "/api/v1/customers", null)).isEqualTo(404);
    }

    @Test
    void aTenantWithoutTheAppIs403AppNotEnabled() throws Exception {
        HttpWorld world = world();
        HttpWorld.Account account = world.newTenant();
        replica.disableApp(account.id().value(), "billing");
        String token = world.newUserToken(account, List.of("billing:customer:read").toArray(String[]::new));

        var result = world.call(HttpMethod.GET, account.host(), token, "/api/v1/customers", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("app-not-enabled");
    }
}
