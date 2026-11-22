package dev.ngb.billing.boot.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.openapi.CallerLabelCustomizer;
import dev.ngb.core.openapi.DescriptionsOpenApiCustomizer;
import dev.ngb.core.openapi.ErrorResponsesOpenApiCustomizer;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.openapi.PermissionCatalogs;
import dev.ngb.core.spring.query.ListQueryResolver;
import dev.ngb.core.spring.security.PermissionInterceptor;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.billing.session.api.portal.PortalAuthorizer;
import dev.ngb.billing.session.api.portal.RequiresPortalPermission;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebConfiguration implements WebMvcConfigurer {

    private final Authorizer authorizer;
    private final PortalAuthorizer portalAuthorizer;

    WebConfiguration(Authorizer authorizer, PortalAuthorizer portalAuthorizer) {
        this.authorizer = authorizer;
        this.portalAuthorizer = portalAuthorizer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new PermissionInterceptor(authorizer, List.of(new PortalPermissionCheck(portalAuthorizer))));
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

    @Bean
    CallerResolver callerResolver(CurrentActor currentActor) {
        return new CallerResolver(currentActor);
    }

    @Bean
    OpenAPI billingOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Billing API").version("v1"))
                .servers(List.of(new Server().url("/").description("Any Billing hostname: platform.<base-domain> or <tenant-code>.<base-domain>")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Billing User signed in through Keycloak"))
                        .addSecuritySchemes("suiteToken", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Platform suite token for a Tenant System; obtain it from POST /oauth/token on Platform"))
                        .addSecuritySchemes("portalSession", new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
                                .name("billing_portal")
                                .description("Billing Session cookie, obtained by exchanging the single-use portal token")));
    }

    /** Documents the permission each operation requires, straight from the annotation that enforces it. */
    @Bean
    OperationCustomizer requiredPermission() {
        return (operation, handlerMethod) -> {
            RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
            if (required != null) {
                operation.addExtension("x-required-permission", required.value());
            }
            RequiresPortalPermission portal = handlerMethod.getMethodAnnotation(RequiresPortalPermission.class);
            if (portal != null) {
                operation.addExtension("x-required-portal-permission", portal.value().code());
            }
            return operation;
        };
    }

    /**
     * Documents both outcomes of a repeatable create. A handler that returns {@code ResponseEntity} is generated with a
     * single 200, so the body schema found there is listed under 201 for a new resource and under 200 for a repeat.
     */
    @Bean
    OperationCustomizer repeatableCreates() {
        return (operation, handlerMethod) -> {
            RepeatableCreate create = handlerMethod.getMethodAnnotation(RepeatableCreate.class);
            if (create == null || operation.getResponses() == null) {
                return operation;
            }
            ApiResponse generated = operation.getResponses().get("200");
            Content body = generated == null ? null : generated.getContent();
            ApiResponses responses = new ApiResponses()
                    .addApiResponse("201", new ApiResponse().description("Created").content(body))
                    .addApiResponse("200", new ApiResponse().description(create.repeated()).content(body));
            operation.getResponses().forEach((code, response) -> {
                if (!responses.containsKey(code)) {
                    responses.addApiResponse(code, response);
                }
            });
            operation.setResponses(responses);
            return operation;
        };
    }

    /**
     * Documents a conditional update: the operation answers an {@code ETag} on success and a 412 when the caller's
     * {@code If-Match} is stale. Driven by the same {@link ConditionalUpdate} marker the handler carries, so the
     * contract cannot drift from the code.
     */
    @Bean
    OperationCustomizer conditionalUpdates() {
        return (operation, handlerMethod) -> {
            if (!handlerMethod.hasMethodAnnotation(ConditionalUpdate.class) || operation.getResponses() == null) {
                return operation;
            }
            ApiResponse ok = operation.getResponses().get("200");
            if (ok != null) {
                ok.addHeaderObject("ETag", etagHeader("The resource version after this change, as a strong tag"));
            }
            operation.getResponses().addApiResponse("412", new ApiResponse()
                    .description("The resource changed since the caller read it; the current ETag is returned in the header")
                    .addHeaderObject("ETag", etagHeader("The current version")));
            return operation;
        };
    }

    private static Header etagHeader(String description) {
        return new Header().description(description).schema(new StringSchema().example("\"3\""));
    }

    /**
     * Derives every operation's caller label from its declaration and checks it against the permission catalog. A
     * portal permission is Billing's spelling of the customer-session caller.
     */
    @Bean
    CallerLabelCustomizer callerLabels() {
        return new CallerLabelCustomizer(
                PermissionCatalogs.fromClasspath(),
                Map.of(RequiresPortalPermission.class, Caller.CUSTOMER_SESSION));
    }
}
