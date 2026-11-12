package dev.ngb.platform.tenancy.application.entitlement.port;

import java.util.Set;

/** The apps the suite currently offers, one per permission catalog under contracts/permissions. */
public interface AppCatalog {

    Set<String> appCodes();
}
