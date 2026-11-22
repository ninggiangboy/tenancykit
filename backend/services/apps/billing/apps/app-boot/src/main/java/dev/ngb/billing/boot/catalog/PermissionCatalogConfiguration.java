package dev.ngb.billing.boot.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Packages the suite's permission catalogs once, so the OpenAPI customizers and any test share one reader. */
@Configuration(proxyBeanMethods = false)
class PermissionCatalogConfiguration {

    @Bean
    BillingPermissionCatalog billingPermissionCatalog() {
        return new BillingPermissionCatalog();
    }
}
