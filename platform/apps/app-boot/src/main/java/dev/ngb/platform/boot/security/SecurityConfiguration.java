package dev.ngb.platform.boot.security;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.access.application.user.query.MeQueries;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
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
    SecurityFilterChain apiSecurity(
            HttpSecurity http, PlatformJwtDecoder decoder, MeQueries users, TenantContext tenantContext) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/oauth/token", "/.well-known/jwks.json", "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(new JwtActorConverter(users, tenantContext))));
        return http.build();
    }

    @Bean
    PlatformJwtDecoder platformJwtDecoder(
            SuiteTokenIssuer issuer,
            @Value("${platform.users.jwk-set-uri:}") String userJwkSetUri,
            @Value("${platform.users.issuer-uri:}") String userIssuerUri) {
        return new PlatformJwtDecoder(issuer, userJwkSetUri, userIssuerUri, "platform");
    }

    @Bean
    SuiteTokenIssuer suiteTokenIssuer(
            PlatformProperties properties, @Value("${platform.tokens.allow-ephemeral-key:false}") boolean allowEphemeralKey) {
        String issuer = properties.tokens() == null || properties.tokens().issuer() == null || properties.tokens().issuer().isBlank()
                ? "http://localhost:8082"
                : properties.tokens().issuer();
        String signingKey = properties.tokens() == null ? null : properties.tokens().signingKey();
        String previous = properties.tokens() == null ? null : properties.tokens().previousSigningKey();
        if ((signingKey == null || signingKey.isBlank()) && !allowEphemeralKey) {
            // An ephemeral key differs per instance, so tokens from one would fail verification at another.
            throw new IllegalStateException(
                    "PLATFORM_TOKEN_SIGNING_KEY is required; set PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=true only for local development");
        }
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
    Authorizer authorizer(CurrentActor currentActor, PermissionCatalog catalog) {
        return code -> {
            Actor actor = currentActor.get();
            if (!actor.has(code)) {
                throw new AccessDeniedException("missing permission " + code);
            }
            // Defence in depth: a tenant actor may never wield a code that only exists in a wider scope.
            if (actor.scope() == PermissionScope.TENANT
                    && catalog.find(code).filter(permission -> permission.validIn(PermissionScope.TENANT)).isEmpty()) {
                throw new AccessDeniedException("permission " + code + " is not valid in tenant scope");
            }
        };
    }
}
