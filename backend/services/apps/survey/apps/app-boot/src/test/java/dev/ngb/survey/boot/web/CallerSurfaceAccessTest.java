package dev.ngb.survey.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.names.ActorNames;
import dev.ngb.survey.shared.SurveyPermissions;
import dev.ngb.survey.support.PostgresContainerConfig;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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
 * The prefix decides who may call a route. A Tenant System token of an entitled tenant reaches the same handler on
 * {@code /system/v1} that a person reaches on {@code /api/v1}; the wrong kind of token is refused with {@code 403}, not
 * {@code 404}, and a non-grantable operation has no {@code /system/v1} twin at all.
 *
 * <p>The tokens are real ES256 suite and Keycloak tokens verified against a JWKS served over HTTP, so the decoder, the
 * actor resolver, the entitlement filter, the tenant-only guard and the prefix filter run exactly as in a deployment.
 */
@AutoConfigureMockMvc
class CallerSurfaceAccessTest extends SurveyIntegrationTest {

    private static final String PLATFORM_ISSUER = "http://platform.test";
    private static final String USER_ISSUER = "http://keycloak.test/realms/tenancykit";

    private static HttpServer jwks;
    private static ECKey platformKey;
    private static RSAKey userKey;

    @Autowired MockMvc mvc;

    @BeforeAll
    static void serveJwks() throws Exception {
        platformKey = new ECKeyGenerator(Curve.P_256).generate();
        userKey = new RSAKeyGenerator(2048).generate();
        String body = new JWKSet(List.of(platformKey.toPublicJWK(), userKey.toPublicJWK())).toString();
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
    static void issuers(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks";
        registry.add("suite.replica.platform-issuer", () -> PLATFORM_ISSUER);
        registry.add("suite.replica.platform-jwk-set-uri", () -> uri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> USER_ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> uri);
    }

    @Test
    void aTenantSystemTokenReachesItsTwinOnTheSystemPrefix() throws Exception {
        Tenant tenant = seedTenant(true);
        String token = tenantSystemToken(tenant, SurveyPermissions.SURVEY_READ);

        assertThat(status("/system/v1/surveys", tenant.host(), token)).isEqualTo(200);
    }

    @Test
    void theSameTenantSystemTokenIsRefusedOnThePersonPrefix() throws Exception {
        Tenant tenant = seedTenant(true);
        String token = tenantSystemToken(tenant, SurveyPermissions.SURVEY_READ);

        assertThat(status("/api/v1/surveys", tenant.host(), token))
                .as("a suite token is at the wrong door on /api/v1")
                .isEqualTo(403);
    }

    @Test
    void aPersonTokenReachesThePersonPrefixAndIsRefusedOnTheSystemOne() throws Exception {
        Tenant tenant = seedTenant(true);
        String token = userToken(tenant, SurveyPermissions.SURVEY_READ);

        assertThat(status("/api/v1/surveys", tenant.host(), token)).isEqualTo(200);
        assertThat(status("/system/v1/surveys", tenant.host(), token))
                .as("a person is at the wrong door on /system/v1")
                .isEqualTo(403);
    }

    @Test
    void aNonGrantableOperationHasNoSystemTwin() throws Exception {
        Tenant tenant = seedTenant(true);
        String token = tenantSystemToken(tenant, SurveyPermissions.SURVEY_READ);

        // survey:settings:manage is never grantable to a Tenant System, so the controller declares TENANT_USER only.
        assertThat(status("/system/v1/settings", tenant.host(), token))
                .as("a route with no /system/v1 twin is not mounted there")
                .isEqualTo(404);
        assertThat(status("/api/v1/settings", tenant.host(), userToken(tenant, SurveyPermissions.SETTINGS_MANAGE)))
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ seeding and tokens

    private record Tenant(String id, String host, String systemId) {}

    private int status(String path, String host, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(path)
                .header("Host", host)
                .with(http -> {
                    http.setServerName(host);
                    return http;
                });
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

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
                insert into app_entitlements (tenant_id, app_code, enabled) values ('%s', 'survey', %s)
                """.formatted(id, entitled));
        return new Tenant(id, host, systemId);
    }

    /** A Platform Tenant System token: the subject names the system, the claims name its tenant and scopes. */
    private static String tenantSystemToken(Tenant tenant, String... scopes) throws Exception {
        return sign(platformKey, PLATFORM_ISSUER, ActorNames.tenantSystem(tenant.systemId()), "survey",
                "tenant_id", tenant.id(), "scope", String.join(" ", scopes));
    }

    /** A Keycloak user token; the replica grant names the permissions the resolver reads. */
    private static String userToken(Tenant tenant, String... permissions) throws Exception {
        String subject = "kc-" + UUID.randomUUID();
        PostgresContainerConfig.executeAsOwner("""
                insert into user_grants (user_id, tenant_id, scope, keycloak_subject, email, display_name, status, permissions, version)
                values (gen_random_uuid(), '%s', 'TENANT', '%s', 'user-%s@example.test', 'User', 'ACTIVE', array[%s], 1)
                """.formatted(tenant.id(), subject, subject,
                        String.join(",", java.util.Arrays.stream(permissions).map(code -> "'" + code + "'").toList())));
        return signUser(USER_ISSUER, subject, "survey");
    }

    private static String signUser(String issuer, String subject, String audience) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(userKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .jwtID(UUID.randomUUID().toString())
                        .build());
        jwt.sign(new RSASSASigner(userKey));
        return jwt.serialize();
    }

    private static String sign(ECKey key, String issuer, String subject, String audience, String... claimPairs)
            throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString());
        for (int i = 0; i + 1 < claimPairs.length; i += 2) {
            claims.claim(claimPairs[i], claimPairs[i + 1]);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
