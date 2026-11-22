package dev.ngb.booking.boot.security;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.suite.security.SuiteActorConverter;
import dev.ngb.core.suite.security.SuiteActorResolver;
import dev.ngb.core.suite.security.SuiteAuthentication;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One stateless chain, two possible principals: a Keycloak user token or a Platform suite token, both verified by
 * {@link SuiteJwtDecoders} against exactly two issuers and the {@code booking} audience.
 *
 * <p>The tenant scope was fixed by the hostname filter before this chain runs; the replica actor resolver then refuses
 * an identity that is not active in that tenant.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            SuiteJwtDecoders decoders,
            SuiteActorResolver actorResolver) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // The generated contract is what a client reads; it exposes no data.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/scalar", "/scalar/**")
                        .permitAll()
                        // The public gate: a customer's browser has no suite token. The short-lived customer token is
                        // the credential and the tenant scope comes from the host. Both spellings are permitted because
                        // compose strips the prefix and the ALB cannot.
                        .requestMatchers("/p/**", "/booking/p/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resource -> resource
                        // The customer token is not a JWT: leave it to the public gate filter, never to this decoder.
                        .bearerTokenResolver(request -> {
                            String uri = request.getRequestURI();
                            return uri.startsWith("/p/") || uri.startsWith("/booking/p/")
                                    ? null
                                    : new DefaultBearerTokenResolver().resolve(request);
                        })
                        .jwt(jwt -> jwt.decoder(decoders)
                                .jwtAuthenticationConverter(new SuiteActorConverter(actorResolver))))
                .headers(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    CurrentActor currentActor() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof SuiteAuthentication suite) {
                return suite.actor();
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
