package dev.ngb.billing.session.api.portal;

/** Answers whether the current portal session carries a permission. */
public interface PortalAuthorizer {

    /** @throws dev.ngb.core.error.AccessDeniedException when the permission is absent */
    void require(PortalPermission permission);
}
