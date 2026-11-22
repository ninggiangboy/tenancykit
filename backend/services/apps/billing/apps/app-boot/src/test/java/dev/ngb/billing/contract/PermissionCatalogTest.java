package dev.ngb.billing.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.billing.session.api.portal.PortalPermission;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.billing.support.RepositoryPaths;
import dev.ngb.core.security.RequiresPermission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Billing's permission vocabulary has one source of truth per kind of code, and this proves they agree:
 * {@link BillingPermissions} with {@code contracts/permissions/billing.yaml}, the codes the endpoints declare with
 * both, and {@link PortalPermission} with the portal permissions the API document requires.
 */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.billing";

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(BillingPermissions.CODES).as("contracts/permissions/billing.yaml and BillingPermissions").isNotEmpty();
        assertThat(BillingPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    @Test
    void theEndpointsDeclareExactlyTheConstants() {
        Set<String> declared = declaredCodes();
        assertThat(declared).as("the scan found the annotated endpoints").isNotEmpty();
        assertThat(declared).as("a code an endpoint declares but no constant holds is a typo the catalog cannot see")
                .containsExactlyInAnyOrderElementsOf(BillingPermissions.CODES);
    }

    @Test
    void thePortalPermissionsAreTheOnesTheApiDocumentRequires() throws IOException {
        Set<String> required = requiredPortalPermissions();
        assertThat(required).as("the API document declares portal permissions").isNotEmpty();
        for (String code : required) {
            assertThat(PortalPermission.fromCode(code).code())
                    .as(code + " is required by an operation but is no portal permission")
                    .isEqualTo(code);
        }
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

    /** The codes of {@code contracts/permissions/billing.yaml}, read from the repository. */
    private static Set<String> catalogCodes() throws IOException {
        JsonNode entries = YAMLMapper.builder().build().readTree(Files.readString(permissionsFile(), StandardCharsets.UTF_8));
        Set<String> codes = new TreeSet<>();
        entries.valueStream().map(entry -> entry.get("code").asString()).forEach(codes::add);
        return codes;
    }

    /** The values of {@code x-required-portal-permission} in {@code contracts/openapi/billing.yaml}. */
    private static Set<String> requiredPortalPermissions() throws IOException {
        String prefix = "x-required-portal-permission:";
        Set<String> codes = new TreeSet<>();
        for (String line : Files.readAllLines(openApiFile(), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(prefix)) {
                codes.add(trimmed.substring(prefix.length()).strip());
            }
        }
        return codes;
    }

    private static Path permissionsFile() {
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/billing.yaml");
    }

    private static Path openApiFile() {
        return RepositoryPaths.repositoryRoot().resolve("contracts/openapi/billing.yaml");
    }
}
