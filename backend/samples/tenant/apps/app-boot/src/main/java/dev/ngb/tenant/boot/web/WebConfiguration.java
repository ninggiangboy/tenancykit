package dev.ngb.tenant.boot.web;

import dev.ngb.core.security.Authorizer;
import dev.ngb.core.spring.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The permission interceptor is the core one, for every handler: an endpoint that declares no access rule fails
 * closed instead of being served. The tenant's roles answer it through {@code TenantAuthorizer}.
 */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final Authorizer authorizer;

    WebConfiguration(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PermissionInterceptor(authorizer));
    }
}
