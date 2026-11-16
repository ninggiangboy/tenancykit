package dev.ngb.crm.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.crm.Application;
import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.support.PostgresContainerConfig;
import dev.ngb.crm.support.RabbitContainerConfig;
import dev.ngb.crm.support.ReplicaFixtures;
import dev.ngb.crm.support.TestTokenFactory;
import dev.ngb.crm.support.TokensConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The three answers the suite's contract promises, over real signed tokens and the real filter chain: a Tenant
 * System's token reads its own tenant's webhook endpoints ({@code 200}), a platform-scope caller is refused
 * ({@code 403}) and a request with no token is refused as unauthenticated ({@code 401}).
 *
 * <p>The endpoint used is a served one — {@code GET /api/v1/webhook-endpoints} — rather than a placeholder, so the
 * same case proves the real surface.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TokensConfig.class)
class SecurityRulesTest {

    private static final String ENDPOINTS = "/api/v1/webhook-endpoints";

    @Autowired
    MockMvc mvc;

    @Autowired
    ReplicaFixtures replica;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("crm.jobs.enabled", () -> "false");
        registry.add("crm.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void aTenantSystemTokenReadsItsOwnTenantsEndpoints() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        UUID system = replica.tenantSystem(tenant.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(system, tenant.id().value(), CrmPermissions.WEBHOOK_MANAGE);

        assertThat(status(tenant.host(), token)).isEqualTo(200);
    }

    @Test
    void aPlatformScopeCallerIsRefused() {
        String platformHost = replica.platformHost();
        String subject = "platform-admin-" + UUID.randomUUID();
        replica.platformUser(subject, CrmPermissions.WEBHOOK_MANAGE);

        assertThat(status(platformHost, TestTokenFactory.userToken(subject))).isEqualTo(403);
    }

    @Test
    void aRequestWithNoTokenIsUnauthenticated() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();

        assertThat(status(tenant.host(), null)).isEqualTo(401);
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        String subject = "user-" + UUID.randomUUID();
        replica.user(tenant.id().value(), subject, CrmPermissions.WEBHOOK_MANAGE);

        assertThat(status(tenant.host(), TestTokenFactory.tokenForOtherAudience(subject))).isEqualTo(401);
    }

    @Test
    void aTokenThatDoesNotHoldThePermissionIsForbidden() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        UUID system = replica.tenantSystem(tenant.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(system, tenant.id().value(), "crm:customer:read");

        assertThat(status(tenant.host(), token)).isEqualTo(403);
    }

    @Test
    void aTenantWithoutTheEntitlementIsRefusedBeforeAnyEndpointRuns() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        String subject = "user-" + UUID.randomUUID();
        replica.user(tenant.id().value(), subject, CrmPermissions.WEBHOOK_MANAGE);
        replica.disableApp(tenant.id().value(), "crm");

        assertThat(status(tenant.host(), TestTokenFactory.userToken(subject))).isEqualTo(403);
    }

    private int status(String host, String token) {
        try {
            MockHttpServletRequestBuilder request = get(ENDPOINTS).header("Host", host);
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
