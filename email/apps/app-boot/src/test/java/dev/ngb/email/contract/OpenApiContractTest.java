package dev.ngb.email.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.email.shared.EmailPermissions;
import dev.ngb.email.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
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
 * The committed API document is the contract a Tenant System reads, so it is validated as data: it must be OpenAPI
 * 3.1, every operation id must be unique, and every permission an operation names must exist in the catalog.
 *
 * <p>It is a hand-maintained resource skeleton until the last feature phase, not a document generated from the
 * controllers: a generated equality check would have to be run for every partially built controller and would make a
 * phase-by-phase build impossible. The endpoint-by-endpoint coverage check is added in phase 10, when every operation
 * exists.
 */
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/email.yaml";

    @Test
    void theDocumentIsOpenApi31AndNamesOnlyCataloguedPermissions() throws IOException {
        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        assertThat(file).as(CONTRACT + " is missing").exists();
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));

        assertThat(root.get("openapi").asString()).startsWith("3.1");
        JsonNode paths = root.get("paths");
        assertThat(paths).as(CONTRACT + " declares no paths").isNotNull();

        Set<String> operationIds = new HashSet<>();
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode operation = paths.get(path).get(method);
                if (!operation.isObject() || operation.get("operationId") == null) {
                    continue;
                }
                String id = operation.get("operationId").asString();
                assertThat(operationIds.add(id))
                        .as("operationId " + id + " appears twice; a client generator needs one id per operation")
                        .isTrue();
                JsonNode permission = operation.get("x-required-permission");
                if (permission != null) {
                    assertThat(EmailPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
        assertThat(operationIds).as("the contract declares no operation").isNotEmpty();
    }

    /**
     * The committed document and the controllers describe one API. The contract is written from the client's side
     * ({@code /email/...}, the route the proxy exposes); a controller path is what the application serves after the
     * proxy strips that prefix, so the comparison normalizes it and the pixel's {@code .gif} suffix.
     *
     * <p>The public tracking controller also maps the unstripped {@code /email/t/...} alias the ALB forwards; it is
     * the same operation as the stripped form, so an alias that normalizes to a declared path is not a second one.
     */
    @Test
    void everyOperationHasOneControllerEndpointAndNoControllerPathIsMissing() throws IOException {
        assertThat(controllerPaths()).containsExactlyInAnyOrderElementsOf(contractPaths());
    }

    private static Set<String> contractPaths() throws IOException {
        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> paths = new TreeSet<>();
        JsonNode declared = root.get("paths");
        for (String path : declared.propertyNames()) {
            JsonNode operations = declared.get(path);
            for (String method : operations.propertyNames()) {
                if (operations.get(method).get("operationId") != null) {
                    paths.add(normalizeContractPath(path));
                    break;
                }
            }
        }
        return paths;
    }

    private static String normalizeContractPath(String path) {
        String normalized = path.startsWith("/email") ? path.substring("/email".length()) : path;
        if (normalized.endsWith("/o/{token}.gif")) {
            normalized = normalized.substring(0, normalized.length() - ".gif".length());
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    private static Set<String> controllerPaths() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ngb.email");
        Set<String> paths = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)) {
                continue;
            }
            for (String classPath : classMappings(javaClass)) {
                for (JavaMethod method : javaClass.getMethods()) {
                    String methodPath = methodMapping(method);
                    if (methodPath != null) {
                        paths.add(normalizeAlias(combine(classPath, methodPath)));
                    }
                }
            }
        }
        return paths;
    }

    /**
     * A class may map more than one path: the public tracking controller serves the stripped form the compose proxy
     * produces and the prefixed form the ALB forwards. Both are the same operation, so the proxy prefix is removed
     * before comparison, exactly as the contract side removes it.
     */
    private static String normalizeAlias(String path) {
        return path.startsWith("/email/t/") ? path.substring("/email".length()) : path;
    }

    private static String[] classMappings(JavaClass javaClass) {
        if (!javaClass.isAnnotatedWith(RequestMapping.class)) {
            return new String[] {""};
        }
        String[] values = javaClass.getAnnotationOfType(RequestMapping.class).value();
        return values.length == 0 ? new String[] {""} : values;
    }

    private static String methodMapping(JavaMethod method) {
        if (method.isAnnotatedWith(GetMapping.class)) {
            return first(method.getAnnotationOfType(GetMapping.class).value());
        }
        if (method.isAnnotatedWith(PostMapping.class)) {
            return first(method.getAnnotationOfType(PostMapping.class).value());
        }
        if (method.isAnnotatedWith(PutMapping.class)) {
            return first(method.getAnnotationOfType(PutMapping.class).value());
        }
        if (method.isAnnotatedWith(PatchMapping.class)) {
            return first(method.getAnnotationOfType(PatchMapping.class).value());
        }
        if (method.isAnnotatedWith(DeleteMapping.class)) {
            return first(method.getAnnotationOfType(DeleteMapping.class).value());
        }
        if (method.isAnnotatedWith(RequestMapping.class)) {
            return first(method.getAnnotationOfType(RequestMapping.class).value());
        }
        return null;
    }

    private static String first(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    private static String combine(String classPath, String methodPath) {
        if (methodPath.isEmpty()) {
            return classPath.isEmpty() ? "/" : classPath;
        }
        String prefix = classPath.endsWith("/") ? classPath.substring(0, classPath.length() - 1) : classPath;
        String suffix = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return (prefix + suffix).isEmpty() ? "/" : prefix + suffix;
    }
}
