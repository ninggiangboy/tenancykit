package dev.ngb.billing.support;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Points the two-issuer decoder at the test key pair, so Billing's security rules are exercised with real signed
 * tokens and no external Keycloak or Platform. Both issuers share the one JWKS; the issuer claim on each token still
 * picks the path the decoder takes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokensConfig {

    /** The base URL the test app sees; MockMvc requests arrive on {@code http://localhost}. */
    public static final String JWK_SET_URI = "http://localhost/test-jwks";

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    TestJwtDecoders testSuiteJwtDecoders() {
        return new TestJwtDecoders();
    }

    @org.springframework.context.annotation.Bean
    DynamicPropertyRegistrar tokenProperties() {
        return registry -> {
            registry.add("suite.replica.platform-issuer", () -> TestTokenFactory.PLATFORM_ISSUER);
            registry.add("suite.replica.platform-jwk-set-uri", () -> JWK_SET_URI);
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokenFactory.USER_ISSUER);
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> JWK_SET_URI);
            // The replica is seeded straight into the tables; the snapshot fetch is never reached in tests.
            registry.add("suite.replica.rebuild-on-start", () -> "false");
            registry.add("suite.replica.client-secret", () -> "test-secret");
        };
    }

    /** The issuer map the decoder is configured with, for a test that wants to assert it. */
    public static Map<String, String> issuers() {
        return Map.of("platform", TestTokenFactory.PLATFORM_ISSUER, "user", TestTokenFactory.USER_ISSUER);
    }
}
