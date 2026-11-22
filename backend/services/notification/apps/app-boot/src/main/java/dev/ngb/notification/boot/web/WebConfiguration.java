package dev.ngb.notification.boot.web;

import dev.ngb.core.openapi.DescriptionsOpenApiCustomizer;
import dev.ngb.core.openapi.ErrorResponsesOpenApiCustomizer;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.CurrentActor;
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
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP edge's guards and its generated contract.
 *
 * <p>{@link PermissionInterceptor} fails closed: an endpoint with no access rule throws rather than being served.
 * {@link TenantScopeAndUserGuard} adds the rules the core cannot express — the scope each endpoint group requires and a
 * user principal on the self-service ones.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebConfiguration implements WebMvcConfigurer {

    private final Authorizer authorizer;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    WebConfiguration(Authorizer authorizer, TenantContext tenantContext, CurrentActor currentActor) {
        this.authorizer = authorizer;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantScopeAndUserGuard(tenantContext, currentActor))
                .addPathPatterns("/api/v1/**");
        registry.addInterceptor(new PermissionInterceptor(authorizer)).addPathPatterns("/api/v1/**");
    }

    /** Fills the {@code ListQuery} parameter of every {@code @Listing} method from the schema it names. */
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
    OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Notification API").version("v1"))
                .servers(List.of(new Server().url("/").description("Any notification hostname: <tenant-code>.<base-domain>/notification")))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("A tenant user signed in through Keycloak")));
    }

    /**
     * Documents which principal may call each operation, from the same handlers {@link TenantScopeAndUserGuard} gates.
     *
     * <p>Template operations are platform-only because a template is the suite's content; the inbox and the caller's own
     * preference are either scope, because a Platform user has an inbox too; everything else is tenant-only.
     */
    @Bean
    OperationCustomizer allowedCallers() {
        return (operation, handlerMethod) -> {
            RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
            if (required != null) {
                operation.addExtension("x-required-permission", required.value());
            }
            if (handlerMethod.hasMethodAnnotation(RequiresPermission.class)
                    || handlerMethod.hasMethodAnnotation(Authenticated.class)) {
                operation.addExtension("x-allowed-callers", callersOf(handlerMethod));
            }
            return operation;
        };
    }

    private static List<String> callersOf(HandlerMethod method) {
        String controller = method.getBeanType().getSimpleName();
        return switch (controller) {
            case "TemplateController" -> List.of("platform-user");
            case "NotificationController", "NotificationPreferenceController" ->
                    List.of("tenant-user", "platform-user");
            default -> List.of("tenant-user");
        };
    }
}
