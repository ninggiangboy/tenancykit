package dev.ngb.survey.boot.security;

import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.suite.security.SuiteAuthentication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The caller of the current request.
 *
 * <p>It is kept apart from {@code SecurityConfiguration} because that class exists only in a servlet application,
 * while beans that take a {@link CurrentActor} are also created in the {@code migrate} runtime, which has no web
 * server. It reads only the security context, so outside a request it answers nothing and refuses when asked.
 */
@Configuration(proxyBeanMethods = false)
class ActorConfiguration {

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
}
