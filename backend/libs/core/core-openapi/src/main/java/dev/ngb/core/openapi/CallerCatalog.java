package dev.ngb.core.openapi;

import dev.ngb.core.security.PermissionScope;
import java.util.Set;

/**
 * What the permission catalog says about a code. The label derivation compares the callers an endpoint declares with
 * this, so the annotation a reader sees and the catalog Platform validates credentials and roles against cannot
 * drift.
 */
public interface CallerCatalog {

    /** Whether a code exists in any catalog packaged on the classpath. */
    boolean catalogues(String code);

    /** The scopes a code applies in; empty means an internal, service-client code. */
    Set<PermissionScope> scopes(String code);

    /** Whether a Tenant System credential may hold the code. */
    boolean grantableToTenantSystem(String code);
}
