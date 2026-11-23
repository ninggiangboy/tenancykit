package dev.ngb.survey.boot.web;

import dev.ngb.core.openapi.CallerLabelCustomizer;
import dev.ngb.core.openapi.CallerSurfaceOpenApiCustomizer;
import dev.ngb.core.openapi.PermissionCatalogs;
import dev.ngb.core.openapi.DescriptionsOpenApiCustomizer;
import dev.ngb.core.openapi.ErrorResponsesOpenApiCustomizer;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.spring.query.ListQueryResolver;
import dev.ngb.core.spring.security.PermissionInterceptor;
import dev.ngb.core.spring.web.CallerPrefixMapping;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.boot.config.SuiteProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The HTTP edge's guards and its generated contract.
 *
 * <p>{@link PermissionInterceptor} fails closed: an endpoint with no access rule throws rather than being served.
 * {@link TenantOnlyGuard} adds the rule the core cannot express — survey data is tenant data, so the platform scope is
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
        // The caller prefixes, not the path a controller declares: both twins of a survey operation are guarded.
        registry.addInterceptor(new TenantOnlyGuard(tenantContext)).addPathPatterns("/api/v1/**", "/system/v1/**");
        registry.addInterceptor(new PermissionInterceptor(authorizer)).addPathPatterns("/api/v1/**", "/system/v1/**");
    }

    /**
     * Mounts every handler under the prefixes its declared callers imply, so a controller declares only its resource
     * path. It must be created after the framework has registered its mappings, which the bean dependency guarantees.
     */
    @Bean
    CallerPrefixMapping callerPrefixes(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping, SuiteProperties suite) {
        return new CallerPrefixMapping(mapping, suite.appCode(), false);
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

    /** Derives every operation's caller label from its declaration and checks it against the permission catalog. */
    @Bean
    CallerLabelCustomizer callerLabels() {
        return new CallerLabelCustomizer(PermissionCatalogs.fromClasspath());
    }

    /** Narrows a route documented at two prefixes to the one caller each prefix serves, and pins a twin's id. */
    @Bean
    CallerSurfaceOpenApiCustomizer callerSurfaces() {
        return new CallerSurfaceOpenApiCustomizer();
    }

    @Bean
    OpenAPI surveyOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Survey API").version("v1")
                        .description("The API a Tenant System uses to build versioned surveys, issue links and read what "
                                + "came back, plus the public endpoints a respondent's browser calls without credentials."))
                .servers(List.of(new Server().url("/survey")
                        .description("Any tenant hostname: <tenant-code>.<base-domain>/survey")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("A suite token issued by Platform for this tenant")));
    }
}
