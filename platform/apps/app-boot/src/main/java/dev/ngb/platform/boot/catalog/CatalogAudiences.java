package dev.ngb.platform.boot.catalog;

import dev.ngb.platform.integration.application.token.port.AudienceCatalog;
import dev.ngb.platform.tenancy.application.entitlement.port.AppCatalog;

/**
 * A token may name any app the deployment offers, and only those: the app list is the permission catalogs on the
 * classpath, so shipping an app's catalog makes its audience mintable without a code change and a name nobody ships is
 * refused.
 *
 * <p>The two ports live in different contexts, so this bridge sits in boot next to the wiring, as the other catalog
 * adapters do.
 */
public class CatalogAudiences implements AudienceCatalog {

    private final AppCatalog apps;

    public CatalogAudiences(AppCatalog apps) {
        this.apps = apps;
    }

    @Override
    public boolean offers(String audience) {
        return apps.appCodes().contains(audience);
    }
}
