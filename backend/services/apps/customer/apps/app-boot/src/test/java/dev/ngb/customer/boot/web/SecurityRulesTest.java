package dev.ngb.customer.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.customer.Application;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.PostgresContainerConfig;
import dev.ngb.customer.support.RabbitContainerConfig;
import dev.ngb.customer.support.ReplicaFixtures;
import dev.ngb.customer.support.TestTokenFactory;
import dev.ngb.customer.support.TokensConfig;
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
 * <p>The endpoint used is a served one — {@code GET /system/v1/webhook-endpoints} — rather than a placeholder, so the
 * same case proves the real surface. The prefix decides who may call it: a Tenant System's token reaches the handler on
 * {@code /system/v1} and is refused at the person door on {@code /api/v1}, which Customer never opens.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TokensConfig.class)
class SecurityRulesTest {

    private static final String ENDPOINTS = "/system/v1/webhook-endpoints";

    /** The person door of the same handler; Customer declares no person caller, so a suite token is refused here. */
    private static final String PERSON_DOOR = "/api/v1/webhook-endpoints";

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
        registry.add("customer.jobs.enabled", () -> "false");
        registry.add("customer.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void aTenantSystemTokenReadsItsOwnTenantsEndpoints() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        UUID system = replica.tenantSystem(tenant.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(system, tenant.id().value(), CustomerPermissions.WEBHOOK_MANAGE);

        assertThat(status(tenant.host(), token)).isEqualTo(200);
    }

    @Test
    void aTenantSystemTokenIsRefusedAtThePersonDoor() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        UUID system = replica.tenantSystem(tenant.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(system, tenant.id().value(), CustomerPermissions.WEBHOOK_MANAGE);

        assertThat(status(tenant.host(), PERSON_DOOR, token))
                .as("a suite token is at the wrong door on /api/v1")
                .isEqualTo(403);
    }

    @Test
    void aPlatformScopeCallerIsRefused() {
        String platformHost = replica.platformHost();
        String subject = "platform-admin-" + UUID.randomUUID();
        replica.platformUser(subject, CustomerPermissions.WEBHOOK_MANAGE);

        assertThat(status(platformHost, PERSON_DOOR, TestTokenFactory.userToken(subject))).isEqualTo(403);
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
        replica.user(tenant.id().value(), subject, CustomerPermissions.WEBHOOK_MANAGE);

        // A person token is of the right kind for /api/v1, so the refusal is the audience check, a 401. The same token
        // on /system/v1 would be refused earlier as the wrong kind of token (403), before the audience is read.
        assertThat(status(tenant.host(), "/api/v1/webhook-endpoints", TestTokenFactory.tokenForOtherAudience(subject)))
                .isEqualTo(401);
    }

    @Test
    void aTokenThatDoesNotHoldThePermissionIsForbidden() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        UUID system = replica.tenantSystem(tenant.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(system, tenant.id().value(), "customer:customer:read");

        assertThat(status(tenant.host(), token)).isEqualTo(403);
    }

    @Test
    void aTenantWithoutTheEntitlementIsRefusedBeforeAnyEndpointRuns() {
        ReplicaFixtures.TenantSeed tenant = replica.tenant();
        String subject = "user-" + UUID.randomUUID();
        replica.user(tenant.id().value(), subject, CustomerPermissions.WEBHOOK_MANAGE);
        replica.disableApp(tenant.id().value(), "customer");

        assertThat(status(tenant.host(), PERSON_DOOR, TestTokenFactory.userToken(subject))).isEqualTo(403);
    }

    private int status(String host, String token) {
        return status(host, ENDPOINTS, token);
    }

    private int status(String host, String path, String token) {
        try {
            MockHttpServletRequestBuilder request = get(path).header("Host", host);
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
