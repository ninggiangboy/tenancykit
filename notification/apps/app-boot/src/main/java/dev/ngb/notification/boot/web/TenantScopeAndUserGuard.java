package dev.ngb.notification.boot.web;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Makes the three authorization rules the service records true, per endpoint group rather than for the whole service.
 *
 * <p>It is deliberately not "notification is tenant-scoped". That blanket rule would leave the suite with no way to edit
 * the content every tenant renders, and it would also let {@code platform.localhost/notification/...} open a door onto
 * every tenant endpoint. So each group states its own scope:
 *
 * <ul>
 *   <li><b>Templates</b> — <b>platform scope only</b>. A template is the suite's content: one row serves every tenant,
 *       so a tenant administrator must not be able to rewrite it. This is the only group reachable at platform scope.
 *   <li><b>Settings and the dispatch ledger</b> — <b>tenant scope only</b>. They configure one tenant and audit that
 *       tenant's own mail; a platform caller has no tenant to configure and no ledger of its own to read here.
 *   <li><b>Inbox and preferences</b> — <b>either scope</b>, with a <b>user</b> principal. A Platform user has an inbox
 *       too, so the platform scope is a legitimate door to the caller's own rows; {@code @Authenticated} also admits a
 *       Tenant System credential, and a machine has no inbox, so a non-user caller is refused.
 * </ul>
 *
 * <p>{@code SuiteEntitlementFilter} passes the platform scope unconditionally and {@code @Authenticated} admits a
 * machine, which is why these rules exist in the first place: the permission codes alone do not express a scope.
 */
public class TenantScopeAndUserGuard implements HandlerInterceptor {

    /** The one group that may run at platform scope; also the group a tenant scope must not reach. */
    private static final List<String> PLATFORM_PREFIXES = List.of("/api/v1/templates");

    /** Tenant-only admin surfaces: they read or write the caller's tenant, so a platform caller has nothing to act on. */
    private static final List<String> TENANT_PREFIXES = List.of("/api/v1/settings", "/api/v1/email-dispatches");

    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    public TenantScopeAndUserGuard(TenantContext tenantContext, CurrentActor currentActor) {
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        boolean platformOnly = matches(PLATFORM_PREFIXES, uri);
        boolean tenantOnly = matches(TENANT_PREFIXES, uri);
        RequestScope scope = tenantContext.current();

        if (platformOnly) {
            if (!(scope instanceof RequestScope.Platform)) {
                throw new AccessDeniedException(
                        "a template is the suite's content and is edited at platform scope only");
            }
            return true;
        }
        if (tenantOnly) {
            if (!(scope instanceof RequestScope.Tenant)) {
                throw new AccessDeniedException("this endpoint is tenant-scoped; the platform scope is refused");
            }
            return true;
        }
        if (currentActor.get().type() != ActorType.USER) {
            throw new AccessDeniedException("this endpoint belongs to a user; a machine credential has no inbox");
        }
        return true;
    }

    private static boolean matches(List<String> prefixes, String uri) {
        return prefixes.stream().anyMatch(uri::startsWith);
    }
}
