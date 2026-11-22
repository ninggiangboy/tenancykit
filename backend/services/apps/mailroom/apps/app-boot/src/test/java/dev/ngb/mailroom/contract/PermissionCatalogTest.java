package dev.ngb.mailroom.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.mailroom.shared.MailroomPermissions;
import dev.ngb.mailroom.support.RepositoryPaths;
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

/** The constants, the catalog file and the endpoint annotations are one set. */
class PermissionCatalogTest {

    private static final String ROOT = "dev.ngb.mailroom";

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(MailroomPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    /** Every catalog code is enforced by at least one endpoint, so no code is documentation only. */
    @Test
    void everyCatalogCodeIsEnforced() {
        assertThat(declaredCodes()).containsExactlyInAnyOrderElementsOf(MailroomPermissions.CODES);
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
        assertThat(classes.stream()
                        .filter(javaClass -> javaClass.isAnnotatedWith(
                                org.springframework.web.bind.annotation.RestController.class))
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(PermissionCatalogTest::isEndpoint)
                        .count())
                .as("the scan found the controllers")
                .isGreaterThan(20);
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
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/mailroom.yaml");
    }
}
