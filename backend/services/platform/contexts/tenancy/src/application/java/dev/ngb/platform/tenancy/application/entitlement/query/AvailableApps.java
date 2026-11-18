package dev.ngb.platform.tenancy.application.entitlement.query;

import java.util.Set;

/** The apps the suite offers, for a read that lists every app with a tenant's state of it. */
public interface AvailableApps {

    Set<String> appCodes();
}
