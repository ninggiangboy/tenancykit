package dev.ngb.platform.boot.web;

import dev.ngb.core.openapi.DescriptionsOpenApiCustomizer;
import dev.ngb.core.openapi.ErrorResponsesOpenApiCustomizer;
import dev.ngb.core.openapi.ListingOpenApiCustomizer;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.ListQueryResolver;
import dev.ngb.core.spring.security.PermissionInterceptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
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

    WebConfiguration(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Platform API").version("v1"))
                .servers(List.of(new Server().url("/").description("The Platform hostname, e.g. platform.<base-domain>")));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PermissionInterceptor(authorizer));
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
}
