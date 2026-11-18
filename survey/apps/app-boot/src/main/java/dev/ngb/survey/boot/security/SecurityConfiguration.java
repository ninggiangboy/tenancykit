package dev.ngb.survey.boot.security;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.suite.security.SuiteActorConverter;
import dev.ngb.core.suite.security.SuiteActorResolver;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One stateless chain, two possible principals: a Keycloak user token or a Platform suite token, both verified by
 * {@link SuiteJwtDecoders} against exactly two issuers and the {@code survey} audience.
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
            SuiteActorResolver actorResolver,
            dev.ngb.survey.collection.adapter.guard.web.PublicGuardFilter publicGuard) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // The rate and body limits run before authentication, because a public request carries no token and
                // the cost of parsing one must not be reachable without an allowance.
                .addFilterBefore(
                        publicGuard,
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // The generated contract is what a client reads; it exposes no data.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml")
                        .permitAll()
                        // The public fill surface: a respondent's browser has no suite token. The signed link token is
                        // the credential and the tenant scope comes from the host. Both spellings are permitted because
                        // compose strips the prefix and the ALB cannot.
                        .requestMatchers("/p/**", "/survey/p/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoders)
                                .jwtAuthenticationConverter(new SuiteActorConverter(actorResolver))))
                .headers(Customizer.withDefaults());
        return http.build();
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
