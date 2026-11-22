package dev.ngb.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.notification.shared.NotificationPermissions;
import dev.ngb.notification.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The constants, the catalog file and the endpoint annotations are one set. */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.notification";

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(NotificationPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    @Test
    void theEndpointsDeclareExactlyTheConstants() {
        Set<String> declared = declaredCodes();
        assertThat(declared).as("the scan found the annotated endpoints").isNotEmpty();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(NotificationPermissions.CODES);
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
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/notification.yaml");
    }
}
