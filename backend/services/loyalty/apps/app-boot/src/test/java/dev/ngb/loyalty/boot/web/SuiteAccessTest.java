package dev.ngb.loyalty.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.names.ActorNames;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import dev.ngb.loyalty.support.PostgresContainerConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The four answers every endpoint must give: a Tenant System token of an entitled tenant gets through, a platform-scope
 * token is refused because no loyalty fact belongs to Platform, a request with no token is unauthenticated, and a tenant
 * that has not enabled loyalty is refused before its identity is even resolved.
 *
 * <p>The tokens are real ES256 suite tokens minted here and verified against a JWKS served over HTTP, so the decoder,
 * the actor resolver, the entitlement filter and the tenant-only guard all run exactly as in a deployment.
 */
@AutoConfigureMockMvc
class SuiteAccessTest extends LoyaltyIntegrationTest {

    private static final String PLATFORM_ISSUER = "http://platform.test";
    private static final String PLATFORM_TENANT = "00000000-0000-7000-8000-000000000001";

    private static HttpServer jwks;
    private static ECKey signingKey;

    @Autowired MockMvc mvc;

    @BeforeAll
    static void servePlatformJwks() throws Exception {
        signingKey = new ECKeyGenerator(Curve.P_256).generate();
        String body = new JWKSet(signingKey.toPublicJWK()).toString();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        jwks.start();
    }

    @AfterAll
    static void stopJwks() {
        jwks.stop(0);
    }

    @DynamicPropertySource
    static void platformTokens(DynamicPropertyRegistry registry) {
        registry.add("suite.replica.platform-issuer", () -> PLATFORM_ISSUER);
        registry.add("suite.replica.platform-jwk-set-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
    }

    @Test
    void anEntitledTenantWithAWebhookPermissionGets200() throws Exception {
        Tenant tenant = seedTenant(true);

        int status = mvc.perform(asTenant(tenant, "/api/v1/webhook-endpoints")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    @Test
    void aRequestWithNoTokenIs401() throws Exception {
        Tenant tenant = seedTenant(true);

        int status = mvc.perform(onHost("/api/v1/webhook-endpoints", tenant.host())).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    void thePlatformScopeIs403() throws Exception {
        Tenant platform = seedPlatformSystem();

        int status = mvc.perform(asTenant(platform, "/api/v1/webhook-endpoints")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void aTenantWithoutTheEntitlementIs403EvenWithAValidToken() throws Exception {
        Tenant tenant = seedTenant(false);

        int status = mvc.perform(asTenant(tenant, "/api/v1/webhook-endpoints")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void aTokenMintedForAnotherAppIsRefused() throws Exception {
        Tenant tenant = seedTenant(true);

        int status = mvc.perform(onHost("/api/v1/webhook-endpoints", tenant.host())
                        .header("Authorization", "Bearer " + token(tenant.systemId(), tenant.id(), "billing")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    // ------------------------------------------------------------------ seeding and tokens

    private record Tenant(String id, String host, String systemId) {}

    private static Tenant seedTenant(boolean entitled) {
        String id = UUID.randomUUID().toString();
        String host = "t" + id.replace("-", "") + ".localhost";
        String systemId = UUID.randomUUID().toString();
        PostgresContainerConfig.executeAsOwner("""
                insert into tenants (id, code, name, status, created_at) values ('%s', 'code-%s', 'Tenant', 'ACTIVE', now())
                """.formatted(id, id));
        PostgresContainerConfig.executeAsOwner("""
                insert into tenant_domains (id, tenant_id, hostname, kind, created_at)
                values (gen_random_uuid(), '%s', '%s', 'TENANT_SUBDOMAIN', now())
                """.formatted(id, host));
        PostgresContainerConfig.executeAsOwner("""
                insert into tenant_systems (id, tenant_id, name, status, created_at)
                values ('%s', '%s', 'pos', 'ACTIVE', now())
                """.formatted(systemId, id));
        PostgresContainerConfig.executeAsOwner("""
                insert into app_entitlements (tenant_id, app_code, enabled) values ('%s', 'loyalty', %s)
                """.formatted(id, entitled));
        return new Tenant(id, host, systemId);
    }

    private static Tenant seedPlatformSystem() {
        String systemId = UUID.randomUUID().toString();
        PostgresContainerConfig.executeAsOwner("""
                insert into tenant_domains (id, hostname, kind, created_at)
                values (gen_random_uuid(), 'platform.localhost', 'PLATFORM', now())
                on conflict (hostname) do nothing
                """);
        PostgresContainerConfig.executeAsOwner("""
                insert into tenant_systems (id, tenant_id, name, status, created_at)
                values ('%s', '%s', 'console', 'ACTIVE', now())
                """.formatted(systemId, PLATFORM_TENANT));
        return new Tenant(PLATFORM_TENANT, "platform.localhost", systemId);
    }

    private MockHttpServletRequestBuilder asTenant(Tenant tenant, String path) throws Exception {
        return onHost(path, tenant.host())
                .header("Authorization", "Bearer " + token(tenant.systemId(), tenant.id(), "loyalty"));
    }

    private static MockHttpServletRequestBuilder onHost(String path, String host) {
        return get(path)
                .header("Host", host)
                .with(request -> {
                    request.setServerName(host);
                    return request;
                });
    }

    /**
     * A Tenant System suite token as Platform mints it: the subject is {@code tenant-system/<id>}, and the tenant and
     * the scopes ride along.
     */
    private static String token(String systemId, String tenantId, String audience) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(signingKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(PLATFORM_ISSUER)
                        .subject(ActorNames.tenantSystem(systemId))
                        .audience(audience)
                        .claim("tenant_id", tenantId)
                        .claim("scope", LoyaltyPermissions.WEBHOOK_MANAGE)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .jwtID(UUID.randomUUID().toString())
                        .build());
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }
}
