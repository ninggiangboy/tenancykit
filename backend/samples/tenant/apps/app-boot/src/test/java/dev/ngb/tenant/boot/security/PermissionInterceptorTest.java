package dev.ngb.tenant.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.security.PermissionInterceptor;
import dev.ngb.tenant.identity.api.security.AuthenticatedUser;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.api.security.TenantPermissions;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * Proves the tenant system's endpoints fail closed: an endpoint that declares no access rule is refused rather than
 * served, a declared permission is answered from the tenant's roles, and a caller without one is refused.
 */
class PermissionInterceptorTest {

    private final PermissionInterceptor interceptor = new PermissionInterceptor(new TenantAuthorizer());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anEndpointWithoutAnAccessRuleIsRefused() {
        authenticate(Role.ADMIN);
        assertThatThrownBy(() -> interceptor.preHandle(request(), response(), handler("unannotated")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no access rule");
    }

    @Test
    void aDeclaredPermissionIsCheckedAgainstTheRole() {
        authenticate(Role.VIEWER);
        assertThatThrownBy(() -> interceptor.preHandle(request(), response(), handler("manageUsers")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("missing permission user:manage");
    }

    @Test
    void aRoleThatHoldsThePermissionIsServed() {
        authenticate(Role.ADMIN);
        assertThat(interceptor.preHandle(request(), response(), handler("manageUsers"))).isTrue();
    }

    @Test
    void aPermissionNoRoleKnowsIsRefused() {
        authenticate(Role.ADMIN);
        assertThatThrownBy(() -> interceptor.preHandle(request(), response(), handler("unknownPermission")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("unknown permission unknown:code");
    }

    @Test
    void aCallerWithoutAAuthenticatedUserIsRefused() {
        assertThatThrownBy(() -> interceptor.preHandle(request(), response(), handler("manageUsers")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAuthenticatedEndpointNeedsNoPermission() {
        authenticate(Role.VIEWER);
        assertThat(interceptor.preHandle(request(), response(), handler("authenticated"))).isTrue();
    }

    @Test
    void aPublicEndpointNeedsNoActor() {
        assertThat(interceptor.preHandle(request(), response(), handler("publicEndpoint"))).isTrue();
    }

    private static void authenticate(Role role) {
        AuthenticatedUser principal =
                new AuthenticatedUser(UUID.randomUUID(), "user@example.test", "Test User", role);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private static HandlerMethod handler(String method) {
        try {
            Method target = Endpoints.class.getDeclaredMethod(method);
            return new HandlerMethod(new Endpoints(), target);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no such endpoint: " + method, e);
        }
    }

    /** The shapes an endpoint can have; the interceptor reads the annotations off these methods. */
    static class Endpoints {

        void unannotated() {}

        @Authenticated
        void authenticated() {}

        @PublicEndpoint
        void publicEndpoint() {}

        @RequiresPermission(TenantPermissions.USER_MANAGE)
        void manageUsers() {}

        @RequiresPermission("unknown:code")
        void unknownPermission() {}
    }
}
