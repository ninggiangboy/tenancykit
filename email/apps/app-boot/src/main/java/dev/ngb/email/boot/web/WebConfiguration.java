package dev.ngb.email.boot.web;

import dev.ngb.core.security.Authorizer;
import dev.ngb.core.spring.security.PermissionInterceptor;
import dev.ngb.core.tenancy.TenantContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP edge's guards and its generated contract.
 *
 * <p>{@link PermissionInterceptor} fails closed: an endpoint with no access rule throws rather than being served.
 * {@link TenantOnlyGuard} adds the rule the core cannot express — email data is tenant data, so the platform scope is
 * refused even though the entitlement filter lets it through.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebConfiguration implements WebMvcConfigurer {

    private final Authorizer authorizer;
    private final TenantContext tenantContext;

    WebConfiguration(Authorizer authorizer, TenantContext tenantContext) {
        this.authorizer = authorizer;
        this.tenantContext = tenantContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantOnlyGuard(tenantContext)).addPathPatterns("/api/v1/**");
        registry.addInterceptor(new PermissionInterceptor(authorizer)).addPathPatterns("/api/v1/**");
    }

    @Bean
    OpenAPI emailOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Email API").version("v1"))
                .servers(List.of(new Server().url("/").description("Any email hostname: <tenant-code>.<base-domain>/email")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("A suite token issued by Platform for this tenant")));
    }
}
