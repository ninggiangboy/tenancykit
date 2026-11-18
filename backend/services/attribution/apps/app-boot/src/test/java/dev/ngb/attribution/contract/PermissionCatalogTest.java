package dev.ngb.attribution.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.support.RepositoryPaths;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
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
 * The code constants, the catalog file and the endpoint annotations are one set.
 *
 * <p>The skeleton serves one endpoint, so the coverage rule is stated in the direction that holds from the first
 * controller onwards — every declared code is in the catalog, and every endpoint declares an access rule — while the
 * reverse direction (every catalog code enforced by an endpoint, every operation backed by a controller) arrives with
 * the last feature phase, when every operation exists.
 */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.attribution";

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(AttributionPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    /** Every code an endpoint declares must be catalogued, or the right it names is unenforceable. */
    @Test
    void everyDeclaredCodeIsInTheCatalog() {
        assertThat(declaredCodes())
                .as("an endpoint declares a code the catalog does not hold")
                .isNotEmpty()
                .isSubsetOf(AttributionPermissions.CODES);
    }

    /** Every endpoint declares a permission, authenticity or public intent, so nothing is open by accident. */
    @Test
    void everyEndpointDeclaresAnAccessRule() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
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
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        return classes.stream()
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
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/attribution.yaml");
    }
}
