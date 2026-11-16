package dev.ngb.crm.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.crm.shared.CrmPermissions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The constants, the catalog file and the endpoint annotations are one set.
 *
 * <p>The catalog is read from the classpath copy the build packages from {@code contracts/permissions/crm.yaml}, so the
 * test cannot pass against a stale duplicate.
 */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.crm";

    /**
     * The codes the contract already defines for endpoints that do not exist yet: the export, the orders, the
     * activities and the erasure. Each change that adds one of these endpoints removes its code here, so the set
     * shrinking is what records the progress, and a code that no endpoint enforces cannot silently stay behind.
     */
    private static final Set<String> NOT_YET_SERVED = Set.of(
            CrmPermissions.CUSTOMER_EXPORT,
            CrmPermissions.CUSTOMER_ERASE,
            CrmPermissions.ACTIVITY_READ,
            CrmPermissions.ACTIVITY_RECORD);

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(CrmPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    /** Every catalog code is either enforced by an endpoint or listed as a later phase's. */
    @Test
    void everyCatalogCodeIsEnforcedOrNotServedYet() {
        Set<String> declared = declaredCodes();
        assertThat(CrmPermissions.CODES).containsAll(declared);
        Set<String> pending = new TreeSet<>(CrmPermissions.CODES);
        pending.removeAll(declared);
        assertThat(pending)
                .as("a code no endpoint enforces must be listed as not served yet")
                .containsExactlyInAnyOrderElementsOf(NOT_YET_SERVED);
    }

    /** Every endpoint declares a permission, authenticity or public intent, so nothing is open by accident. */
    @Test
    void everyEndpointDeclaresAnAccessRule() {
        JavaClasses classes = productionClasses();
        List<String> open = controllerMethods(classes).stream()
                .filter(method -> !method.isAnnotatedWith(RequiresPermission.class)
                        && !method.isAnnotatedWith(Authenticated.class)
                        && !method.isAnnotatedWith(PublicEndpoint.class))
                .map(JavaMethod::getFullName)
                .sorted()
                .toList();
        assertThat(open).as("an endpoint must declare a permission, be authenticated, or be public").isEmpty();
        assertThat(controllerMethods(classes)).as("the scan found the controllers").isNotEmpty();
    }

    /** crm has no public surface: every served endpoint names a permission. */
    @Test
    void everyServedEndpointRequiresAPermission() {
        assertThat(controllerMethods(productionClasses()).stream()
                        .map(method -> method.tryGetAnnotationOfType(RequiresPermission.class))
                        .toList())
                .as("a crm endpoint without a permission would be open to any authenticated caller")
                .allSatisfy(annotation -> assertThat(annotation).isPresent());
    }

    private static List<JavaMethod> controllerMethods(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(
                        org.springframework.web.bind.annotation.RestController.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(PermissionCatalogTest::isEndpoint)
                .toList();
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    private static boolean isEndpoint(JavaMethod method) {
        return method.isAnnotatedWith(GetMapping.class)
                || method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class)
                || method.isAnnotatedWith(RequestMapping.class);
    }

    private static Set<String> declaredCodes() {
        return productionClasses().stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.tryGetAnnotationOfType(RequiresPermission.class).stream())
                .map(RequiresPermission::value)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> catalogCodes() throws IOException {
        try (InputStream catalog = PermissionCatalogTest.class.getResourceAsStream("/permissions/crm.yaml")) {
            assertThat(catalog).as("the build packages contracts/permissions into the jar").isNotNull();
            JsonNode entries = YAMLMapper.builder()
                    .build()
                    .readTree(new String(catalog.readAllBytes(), StandardCharsets.UTF_8));
            Set<String> codes = new TreeSet<>();
            entries.valueStream().map(entry -> entry.get("code").asString()).forEach(codes::add);
            return codes;
        }
    }
}
