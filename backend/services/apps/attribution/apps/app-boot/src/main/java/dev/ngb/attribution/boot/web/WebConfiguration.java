package dev.ngb.attribution.boot.web;

import dev.ngb.core.openapi.DescriptionsOpenApiCustomizer;
import dev.ngb.core.openapi.ErrorResponsesOpenApiCustomizer;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.ListQueryResolver;
import dev.ngb.core.spring.security.PermissionInterceptor;
import dev.ngb.core.tenancy.TenantContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
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
 * {@link TenantOnlyGuard} adds the rule the core cannot express — attribution data is tenant data, so the platform
 * scope is refused even though the entitlement filter lets it through.
 *
 * <p>The guards cover the authenticated surface only. The public paths — {@code /go/*}, {@code /t/v1/*} and the one
 * OAuth callback under {@code /attribution/t/*} — carry no suite token and no permission.
 *
 * <p>Every list endpoint answers on the shared list-query contract, so the one {@link ListQueryResolver} fills the
 * {@code ListQuery} parameter of a {@code @Listing} method and the one {@link ListingOpenApiCustomizer} writes that
 * method's documented parameters from the same schema the resolver validates against.
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
        registry.addInterceptor(new TenantOnlyGuard(tenantContext)).addPathPatterns("/api/v1/**", "/campaigns/**", "/links/**",
                "/goals/**", "/conversions/**", "/reports/**", "/connections/**", "/settings/**", "/data-subjects/**");
        registry.addInterceptor(new PermissionInterceptor(authorizer)).addPathPatterns("/api/v1/**", "/campaigns/**", "/links/**",
                "/goals/**", "/conversions/**", "/reports/**", "/connections/**", "/settings/**", "/data-subjects/**");
    }

    /** Supplies the {@code ListQuery} parameter of every {@code @Listing} method from the query string. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ListQueryResolver());
    }

    /** Documents every {@code @Listing} operation from the schema it names. */
    @Bean
    ListingOpenApiCustomizer listingParameters() {
        return new ListingOpenApiCustomizer();
    }

    /** Describes the fields, parameters and bodies the controllers' signatures cannot; the words live in openapi-descriptions.conf. */
    @Bean
    DescriptionsOpenApiCustomizer openApiDescriptions() {
        return new DescriptionsOpenApiCustomizer("openapi-descriptions.conf");
    }

    /** Documents the errors each operation can answer: the derived ones, and the service's own lines in the same file. */
    @Bean
    ErrorResponsesOpenApiCustomizer openApiErrors() {
        return new ErrorResponsesOpenApiCustomizer("openapi-descriptions.conf");
    }

    /** Documents the permission an operation requires, and marks the public paths that need no token. */
    @Bean
    OperationCustomizer documentedAccess() {
        return (operation, handlerMethod) -> {
            RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
            if (required != null) {
                operation.addExtension("x-required-permission", required.value());
            }
            if (handlerMethod.hasMethodAnnotation(PublicEndpoint.class)) {
                operation.addExtension("x-public", true);
            }
            return operation;
        };
    }

    @Bean
    OpenAPI attributionOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Attribution API").version("v1")
                        .description("The API a Tenant System uses to run tracked marketing links, report conversions, "
                                + "read measurement and keep advertising cost, plus the public redirect and collection "
                                + "endpoints a visitor's browser calls without credentials."))
                .servers(List.of(new Server().url("/attribution")
                        .description("Any tenant hostname: <tenant-code>.<base-domain>/attribution")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("A suite token issued by Platform for this tenant")));
    }
}
