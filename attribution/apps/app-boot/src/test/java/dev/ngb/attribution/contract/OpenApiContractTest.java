package dev.ngb.attribution.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The committed API document is the contract a Tenant System reads, so it is validated as data: it must be OpenAPI
 * 3.1, every operation id must be unique, and every permission an operation names must exist in the catalog.
 *
 * <p>It is a hand-maintained resource skeleton until the last feature phase, not a document generated from the
 * controllers: a generated equality check would have to be run for every partially built controller and would make a
 * phase-by-phase build impossible. The endpoint-by-endpoint coverage check is added alongside the last feature phase,
 * when every operation exists.
 */
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/attribution.yaml";

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
                    assertThat(AttributionPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
        assertThat(operationIds).as("the contract declares no operation").isNotEmpty();
    }

    /** Every operation declares a permission or is explicitly public, so no operation is open by accident. */
    @Test
    void everyOperationDeclaresAPermissionOrIsPublic() throws IOException {
        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode paths = root.get("paths");

        Set<String> unmarked = new TreeSet<>();
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode operation = paths.get(path).get(method);
                if (!operation.isObject() || operation.get("operationId") == null) {
                    continue;
                }
                if (operation.get("x-required-permission") == null
                        && (operation.get("x-public") == null || !operation.get("x-public").asBoolean())) {
                    unmarked.add(method.toUpperCase() + " " + path);
                }
            }
        }
        assertThat(unmarked).as("an operation must name a permission or be marked x-public").isEmpty();
    }

    /**
     * Every catalog code is named by at least one operation, so no code is documentation only. The reverse check — that
     * every operation has a controller — arrives when the controllers do.
     */
    @Test
    void everyCatalogCodeIsNamedByAnOperation() throws IOException {
        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode paths = root.get("paths");

        Set<String> named = new TreeSet<>();
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode permission = paths.get(path).get(method).get("x-required-permission");
                if (permission != null) {
                    named.add(permission.asString());
                }
            }
        }
        assertThat(named).containsExactlyInAnyOrderElementsOf(AttributionPermissions.CODES);
    }
}

