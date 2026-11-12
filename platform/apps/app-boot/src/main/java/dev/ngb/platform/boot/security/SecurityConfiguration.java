package dev.ngb.platform.boot.security;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.platform.boot.config.PlatformProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One stateless chain. The caller is either a user signed in through Keycloak or a service client holding a
 * Platform-issued suite token; how the token is verified and who it names is decided by the issuer it carries.
 * The tenant scope was fixed by the hostname filter before this chain runs.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(PlatformProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, PlatformJwtDecoder decoder) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/oauth/token", "/.well-known/jwks.json", "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(new JwtActorConverter())));
        return http.build();
    }

    @Bean
    PlatformJwtDecoder platformJwtDecoder(
            SuiteTokenIssuer issuer, @Value("${platform.users.jwk-set-uri:}") String userJwkSetUri) {
        return new PlatformJwtDecoder(issuer, userJwkSetUri);
    }

    @Bean
    SuiteTokenIssuer suiteTokenIssuer(PlatformProperties properties) {
        String issuer = properties.tokens() == null || properties.tokens().issuer() == null || properties.tokens().issuer().isBlank()
                ? "http://localhost:8082"
                : properties.tokens().issuer();
        String signingKey = properties.tokens() == null ? null : properties.tokens().signingKey();
        String previous = properties.tokens() == null ? null : properties.tokens().previousSigningKey();
        return new SuiteTokenIssuer(issuer, signingKey, previous);
    }

    @Bean
    CurrentActor currentActor() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof ActorAuthentication actorAuthentication) {
                return actorAuthentication.actor();
            }
            throw new IllegalStateException("the request is not authenticated");
        };
    }

    @Bean
    Authorizer authorizer(CurrentActor currentActor) {
        return code -> {
            Actor actor = currentActor.get();
            if (!actor.has(code)) {
                throw new AccessDeniedException("missing permission " + code);
            }
        };
    }
}
