package dev.ngb.platform.boot.web;

import dev.ngb.core.security.Authorizer;
import dev.ngb.core.spring.security.PermissionInterceptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
