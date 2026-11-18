package dev.ngb.loyalty.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * The constants, the catalog file and the endpoint annotations agree.
 *
 * <p>The direction is one way for now: every code an endpoint enforces must be in the catalog and in the constants, and
 * the catalog and the constants must be one set. The catalog documents the whole app, so most of its codes have no
 * endpoint yet; requiring every code to be enforced would fail until the last phase and buy nothing. A later phase
 * tightens this to equality once every code is served.
 */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.loyalty";

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(LoyaltyPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    /** Every code an endpoint names exists in the catalog, so no endpoint can require a code Platform does not know. */
    @Test
    void everyEnforcedCodeIsInTheCatalog() {
        assertThat(declaredCodes()).isNotEmpty();
        assertThat(LoyaltyPermissions.CODES).containsAll(declaredCodes());
    }

    /** Every endpoint declares a permission, authenticity or public intent, so nothing is open by accident. */
    @Test
    void everyEndpointDeclaresAnAccessRule() {
        JavaClasses classes = production();
        List<String> open = classes.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(
                        org.springframework.web.bind.annotation.RestController.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(PermissionCatalogTest::isEndpoint)
                .filter(method -> !method.isAnnotatedWith(RequiresPermission.class)
                        && !method.isAnnotatedWith(Authenticated.class)
                        && !method.isAnnotatedWith(PublicEndpoint.class))
                .map(JavaMethod::getFullName)
                .sorted()
                .toList();
        assertThat(open).as("an endpoint must declare a permission, be authenticated, or be public").isEmpty();
        assertThat(endpointCount(classes))
                .as("the scan found the webhook endpoints the app serves")
                .isGreaterThanOrEqualTo(6);
    }

    private static long endpointCount(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(
                        org.springframework.web.bind.annotation.RestController.class))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(PermissionCatalogTest::isEndpoint)
                .count();
    }

    private static boolean isEndpoint(JavaMethod method) {
        return method.isAnnotatedWith(GetMapping.class)
                || method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class)
                || method.isAnnotatedWith(RequestMapping.class);
    }

    private static JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    private static Set<String> declaredCodes() {
        return production().stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.tryGetAnnotationOfType(RequiresPermission.class).stream())
                .map(RequiresPermission::value)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> catalogCodes() throws IOException {
        JsonNode entries = YAMLMapper.builder()
                .build()
                .readTree(Files.readString(permissionsFile(), StandardCharsets.UTF_8));
        Set<String> codes = new TreeSet<>();
        entries.valueStream().map(entry -> entry.get("code").asString()).forEach(codes::add);
        return codes;
    }

    private static java.nio.file.Path permissionsFile() {
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/loyalty.yaml");
    }
}
