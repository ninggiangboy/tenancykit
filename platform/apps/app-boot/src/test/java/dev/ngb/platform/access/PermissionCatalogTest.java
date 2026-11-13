package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.access.adapter.catalog.yaml.YamlPermissionCatalog;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.domain.permission.CataloguedPermission;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The catalogs are the suite's one permission list; this proves they load, carry the expected scopes and match the endpoints. */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.platform";

    private final PermissionCatalog catalog = new YamlPermissionCatalog();

    @Test
    void everyAppContributesItsCodes() {
        assertThat(catalog.find("platform:tenant:create")).isPresent();
        assertThat(catalog.find("billing:invoice:read")).isPresent();
        assertThat(catalog.find("billing:webhook:manage")).isPresent();
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

    /** A code an endpoint declares but no catalog holds would be an unenforceable right; the scan is the check. */
    @Test
    void everyDeclaredCodeIsInTheCatalog() {
        Set<String> declared = declaredCodes();
        assertThat(declared).as("the scan found the annotated endpoints").isNotEmpty();
        for (String code : declared) {
            assertThat(catalog.find(code)).as(code + " is declared by an endpoint but is in no catalog").isPresent();
        }
    }

    /** And the other way: every code the platform catalog grants is declared by an endpoint that enforces it. */
    @Test
    void everyCatalogCodeIsDeclaredByAnEndpoint() {
        Set<String> platformCodes = catalog.all().stream()
                .map(CataloguedPermission::code)
                .filter(code -> code.startsWith("platform:"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(declaredCodes()).as("the catalog and the endpoints are one set").containsExactlyInAnyOrderElementsOf(platformCodes);
    }

    /** Every {@code @RequiresPermission} value in the production code, read off the compiled classes. */
    private static Set<String> declaredCodes() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        return classes.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.tryGetAnnotationOfType(RequiresPermission.class).stream())
                .map(RequiresPermission::value)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
