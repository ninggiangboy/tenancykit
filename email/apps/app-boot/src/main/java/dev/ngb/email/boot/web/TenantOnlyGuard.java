package dev.ngb.email.boot.web;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Every email endpoint is tenant data, so a platform-scope caller is refused.
 *
 * <p>The entitlement filter passes the platform scope unconditionally, and the permission codes do not express a scope,
 * so without this rule {@code platform.localhost/email/...} would open a door onto tenant data. A platform
 * administrator manages the suite; it does not own any tenant's templates, receivers or campaigns.
 */
public class TenantOnlyGuard implements HandlerInterceptor {

    private final TenantContext tenantContext;

    public TenantOnlyGuard(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(tenantContext.current() instanceof RequestScope.Tenant)) {
            throw new AccessDeniedException("this endpoint is tenant-scoped; the platform scope is refused");
        }
        return true;
    }
}
