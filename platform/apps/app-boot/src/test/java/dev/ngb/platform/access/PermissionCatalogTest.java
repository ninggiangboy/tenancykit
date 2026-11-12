package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.adapter.catalog.yaml.YamlPermissionCatalog;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import org.junit.jupiter.api.Test;

/** The catalogs are the suite's one permission list; this proves they load and carry the expected scopes. */
class PermissionCatalogTest {

    private final PermissionCatalog catalog = new YamlPermissionCatalog();

    @Test
    void everyAppContributesItsCodes() {
        assertThat(catalog.find("platform:tenant:create")).isPresent();
        assertThat(catalog.find("billing:invoice:read")).isPresent();
        assertThat(catalog.find("notification:webhook:manage")).isPresent();
        assertThat(catalog.find("no:such:code")).isEmpty();
    }

    @Test
    void theScopeOfACodeDecidesWhereItMayBeGranted() {
        assertThat(catalog.codesValidIn(PermissionScope.PLATFORM)).contains("platform:tenant:create", "platform:entitlement:manage");
        assertThat(catalog.codesValidIn(PermissionScope.PLATFORM)).doesNotContain("billing:invoice:read");
        assertThat(catalog.codesValidIn(PermissionScope.TENANT)).contains("billing:invoice:read", "platform:user:read");
        assertThat(catalog.codesValidIn(PermissionScope.TENANT)).doesNotContain("platform:tenant:create");
    }

    @Test
    void aServiceOnlyCodeIsGrantedToNoRole() {
        assertThat(catalog.find("platform:replica:read")).get().satisfies(permission -> {
            assertThat(permission.scopes()).isEmpty();
            assertThat(permission.grantableToTenantSystem()).isFalse();
        });
    }

    @Test
    void aTenantSystemGrantableCodeIsMarkedAsSuch() {
        assertThat(catalog.find("billing:invoice:read")).get().satisfies(permission ->
                assertThat(permission.grantableToTenantSystem()).isTrue());
    }
}
