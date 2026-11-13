package dev.ngb.billing.boot.web;

import dev.ngb.billing.session.api.portal.PortalAuthorizer;
import dev.ngb.billing.session.api.portal.RequiresPortalPermission;
import dev.ngb.core.spring.security.PermissionCheck;
import org.springframework.web.method.HandlerMethod;

/**
 * Gives the core interceptor the one annotation only Billing has: a Billing Session carries its own permission set,
 * so a portal endpoint is checked against the portal authorizer and not against the suite's authorizer. A method
 * without the annotation is left to the core's checks, including the fail-closed default.
 */
final class PortalPermissionCheck implements PermissionCheck {

    private final PortalAuthorizer portalAuthorizer;

    PortalPermissionCheck(PortalAuthorizer portalAuthorizer) {
        this.portalAuthorizer = portalAuthorizer;
    }

    @Override
    public boolean check(HandlerMethod method) {
        RequiresPortalPermission portal = method.getMethodAnnotation(RequiresPortalPermission.class);
        if (portal == null) {
            return false;
        }
        portalAuthorizer.require(portal.value());
        return true;
    }
}
