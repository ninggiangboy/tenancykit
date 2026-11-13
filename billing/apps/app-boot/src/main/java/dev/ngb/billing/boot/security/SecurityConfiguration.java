package dev.ngb.billing.boot.security;

import dev.ngb.billing.boot.config.PortalProperties;
import dev.ngb.billing.boot.portal.PortalCookieSettings;
import dev.ngb.billing.boot.web.PortalCorsConfigurationSource;
import dev.ngb.billing.session.api.portal.CurrentPortalVisitor;
import dev.ngb.billing.session.api.portal.PortalAuthorizer;
import dev.ngb.billing.session.api.portal.PortalSessions;
import dev.ngb.billing.session.api.portal.PortalVisitor;
import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.suite.security.SuiteActorConverter;
import dev.ngb.core.suite.security.SuiteActorResolver;
import dev.ngb.core.suite.security.SuiteAuthentication;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One stateless chain, two possible principals: a Keycloak user token or a Platform suite token, both verified by
 * {@link SuiteJwtDecoders} against exactly two issuers and the {@code billing} audience. The tenant scope was fixed by
 * the hostname filter before this chain runs; the replica actor resolver then refuses an identity that is not active
 * in that tenant.
 *
 * <p>Billing no longer accepts API keys: a Tenant System exchanges its credential at Platform for a suite token.
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
            PortalSessions portalSessions,
            PortalCookieSettings portalCookieSettings,
            PortalCorsConfigurationSource portalCors,
            PortalProperties portal) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(portalCors))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Tests publish a local JWKS here; production points the decoder at the real issuers.
                        .requestMatchers("/test-jwks")
                        .permitAll()
                        // Stripe calls these; the signature of the body is the proof, verified before anything is read.
                        .requestMatchers("/stripe/webhooks/**")
                        .permitAll()
                        // The portal exchanges its single-use token for a cookie here; there is no cookie yet.
                        .requestMatchers("/portal/api/session/exchange")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoders).jwtAuthenticationConverter(new SuiteActorConverter(actorResolver))))
                .headers(Customizer.withDefaults());
        if (portal.enabled()) {
            http.addFilterBefore(
                    new PortalSessionAuthenticationFilter(portalSessions, portalCookieSettings),
                    BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    CurrentActor currentActor() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof ActorAuthentication actorAuthentication) {
                return actorAuthentication.actor();
            }
            // The replica's resource-server converter authenticates a suite/user token as its own type.
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

    @Bean
    PortalCookieSettings portalCookieSettings(PortalProperties portal) {
        return new PortalCookieSettings(portal.secureCookie());
    }

    @Bean
    PortalCorsConfigurationSource portalCorsConfigurationSource() {
        return new PortalCorsConfigurationSource();
    }

    @Bean
    CurrentPortalVisitor currentPortalVisitor() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof PortalSessionAuthentication portal) {
                return portal.visitor();
            }
            throw new IllegalStateException("the request has no portal session");
        };
    }

    @Bean
    PortalAuthorizer portalAuthorizer(CurrentPortalVisitor currentPortalVisitor) {
        return permission -> {
            PortalVisitor visitor = currentPortalVisitor.get();
            if (!visitor.permissions().contains(permission)) {
                throw new AccessDeniedException("missing portal permission " + permission.code());
            }
        };
    }
}
