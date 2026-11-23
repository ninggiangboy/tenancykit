package dev.ngb.core.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.SelfService;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The mechanism mounts one mapping per prefix the callers imply and removes the un-prefixed one. A standalone handler
 * mapping proves it without a full application context: the registered path patterns are the assertion.
 */
class CallerPrefixMappingTest {

    @RestController
    @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
    static class Wallets {
        @GetMapping("/wallets/{id}")
        void get() {}

        @SelfService
        @GetMapping("/wallets/mine")
        void mine() {}
    }

    @RestController
    @Callers(Caller.PLATFORM_USER)
    static class Tenants {
        @GetMapping("/tenants")
        void list() {}
    }

    @RestController
    @Callers(value = Caller.TENANT_SYSTEM, prefix = Callers.Prefix.NONE)
    static class TokenEndpoints {
        @GetMapping("/oauth/token")
        void token() {}
    }

    @RestController
    static class PublicSurface {
        @PublicEndpoint(caller = Caller.PROVIDER)
        @GetMapping("/hooks/{id}")
        void hook() {}
    }

    private static RequestMappingHandlerMapping withPrefixes(Object controller) throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        for (Method method : controller.getClass().getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                mapping.registerMapping(RequestMappingInfo.paths(get.value()).build(), controller, method);
            }
        }
        return mapping;
    }

    private static Set<String> paths(RequestMappingHandlerMapping mapping) {
        Set<String> paths = new HashSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            paths.addAll(info.getPatternValues());
        }
        return paths;
    }

    @Test
    void aTenantKindRouteIsServedUnderBothPrefixesAndNotAtTheRoot() throws Exception {
        RequestMappingHandlerMapping mapping = withPrefixes(new Wallets());
        new CallerPrefixMapping(mapping, "billing", false).afterPropertiesSet();

        Set<String> paths = paths(mapping);
        assertThat(paths).doesNotContain("/wallets/{id}");
        assertThat(paths).contains("/api/v1/wallets/{id}", "/system/v1/wallets/{id}");
    }

    @Test
    void aSelfServiceRouteIsServedOnceUnderMe() throws Exception {
        RequestMappingHandlerMapping mapping = withPrefixes(new Wallets());
        new CallerPrefixMapping(mapping, "billing", false).afterPropertiesSet();

        Set<String> paths = paths(mapping);
        assertThat(paths).contains("/api/v1/me/wallets/mine");
        assertThat(paths).doesNotContain("/wallets/mine", "/api/v1/wallets/mine", "/system/v1/wallets/mine");
    }

    @Test
    void aPlatformRouteIsServedUnderAdmin() throws Exception {
        RequestMappingHandlerMapping mapping = withPrefixes(new Tenants());
        new CallerPrefixMapping(mapping, "platform", false).afterPropertiesSet();

        Set<String> paths = paths(mapping);
        assertThat(paths).doesNotContain("/tenants");
        assertThat(paths).contains("/admin/v1/tenants");
        assertThat(paths).doesNotContain("/api/v1/tenants");
    }

    @Test
    void aStandardPathKeepsItsPath() throws Exception {
        RequestMappingHandlerMapping mapping = withPrefixes(new TokenEndpoints());
        new CallerPrefixMapping(mapping, "platform", false).afterPropertiesSet();

        Set<String> paths = paths(mapping);
        assertThat(paths).contains("/oauth/token");
        assertThat(paths).doesNotContain("/system/v1/oauth/token", "/internal/v1/oauth/token");
    }

    @Test
    void thePublicNamespaceIsMountedOnlyWhenTheAppMovedIt() throws Exception {
        RequestMappingHandlerMapping open = withPrefixes(new PublicSurface());
        new CallerPrefixMapping(open, "workflow", true).afterPropertiesSet();
        assertThat(paths(open)).contains("/public/workflow/hooks/{id}").doesNotContain("/hooks/{id}");

        RequestMappingHandlerMapping stillInside = withPrefixes(new PublicSurface());
        new CallerPrefixMapping(stillInside, "workflow", false).afterPropertiesSet();
        assertThat(paths(stillInside)).contains("/hooks/{id}");
    }
}
