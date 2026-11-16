package dev.ngb.booking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Points the two-issuer decoder at the test key pair, so booking's security rules are exercised with real signed
 * tokens and no external Keycloak or Platform.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TokensConfig {

    @Bean
    @Primary
    TestJwtDecoders testSuiteJwtDecoders() {
        return new TestJwtDecoders();
    }

    @Bean
    DynamicPropertyRegistrar tokenProperties() {
        return registry -> {
            registry.add("suite.replica.platform-issuer", () -> TestTokenFactory.PLATFORM_ISSUER);
            registry.add("suite.replica.platform-jwk-set-uri", () -> "http://localhost/unused-jwks");
            registry.add(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokenFactory.USER_ISSUER);
            registry.add(
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused-jwks");
            registry.add("suite.replica.rebuild-on-start", () -> "false");
            registry.add("suite.replica.client-secret", () -> "test-secret");
        };
    }
}
