package dev.ngb.loyalty.boot.web;

import dev.ngb.core.openapi.CallerLabelCustomizer;
import dev.ngb.core.openapi.PermissionCatalogs;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.spring.query.ListQueryResolver;
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
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP edge's guards and its generated contract.
 *
 * <p>{@link PermissionInterceptor} fails closed: an endpoint with no access rule throws rather than being served.
 * {@link TenantOnlyGuard} adds the rule the core cannot express — every loyalty fact belongs to one Tenant's own
 * members, so the platform scope is refused even though the entitlement filter lets it through.
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

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ListQueryResolver());
    }

    /** Documents every {@code @Listing} operation from the schema it names. */
    @Bean
    ListingOpenApiCustomizer listingParameters() {
        return new ListingOpenApiCustomizer();
    }

    /** Derives every operation's caller label from its declaration and checks it against the permission catalog. */
    @Bean
    CallerLabelCustomizer callerLabels() {
        return new CallerLabelCustomizer(PermissionCatalogs.fromClasspath());
    }

    @Bean
    OpenAPI loyaltyOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Loyalty API").version("v1")
                        .description("The API a Tenant System uses to run its points program: the program, its tiers and "
                                + "promotions, members and their points, orders and returns, redemptions, rewards and vouchers, "
                                + "and the signed webhooks loyalty sends to the Tenant's own endpoints."))
                .servers(List.of(new Server().url("/loyalty")
                        .description("Any tenant hostname: <tenant-code>.<base-domain>/loyalty")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("A suite token issued by Platform for this tenant")));
    }
}
