package dev.ngb.core.spring.security;

import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the permission an endpoint declares. A controller method that declares neither a permission nor
 * {@link Authenticated} (or {@link PublicEndpoint}) fails closed with a server error instead of being served, so no
 * endpoint can be exposed by forgetting an annotation.
 *
 * <p>A service that carries annotations of its own adds a {@link PermissionCheck} instead of a second interceptor,
 * so every endpoint in the suite is guarded by this one class.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private final Authorizer authorizer;
    private final List<PermissionCheck> checks;

    public PermissionInterceptor(Authorizer authorizer) {
        this(authorizer, List.of());
    }

    public PermissionInterceptor(Authorizer authorizer, List<PermissionCheck> checks) {
        this.authorizer = authorizer;
        this.checks = List.copyOf(checks);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || isInfrastructure(method)) {
            return true;
        }
        for (PermissionCheck check : checks) {
            if (check.check(method)) {
                return true;
            }
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required != null) {
            authorizer.require(required.value());
            return true;
        }
        if (method.hasMethodAnnotation(Authenticated.class) || method.hasMethodAnnotation(PublicEndpoint.class)) {
            return true;
        }
        throw new IllegalStateException("endpoint declares no access rule: " + method.getShortLogMessage());
    }

    private static boolean isInfrastructure(HandlerMethod method) {
        String type = method.getBeanType().getName();
        return type.startsWith("org.springdoc.") || type.startsWith("org.springframework.");
    }
}
