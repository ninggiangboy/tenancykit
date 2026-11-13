package dev.ngb.tenant.boot.security;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.security.Authorizer;
import dev.ngb.tenant.identity.api.security.AuthenticatedUser;
import dev.ngb.tenant.identity.api.security.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Answers the core {@link Authorizer} from the tenant's own role model: the code an endpoint declares is mapped back
 * to the {@link Permission} the roles are built from, so an endpoint and a role grant cannot drift. A code no
 * permission knows is refused instead of ignored, and a request without an authenticated business user is refused
 * before anything else.
 */
class TenantAuthorizer implements Authorizer {

    @Override
    public void require(String code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new UnauthorizedException("authentication is required");
        }
        Permission permission = Permission.byCode(code)
                .orElseThrow(() -> new AccessDeniedException("unknown permission " + code));
        if (!user.permits(permission)) {
            throw new AccessDeniedException("missing permission " + code);
        }
    }
}
