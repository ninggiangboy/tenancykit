package dev.ngb.survey.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.openapi.CallerLabelRules;
import dev.ngb.core.openapi.CallerOpenApiSplitter;
import dev.ngb.survey.shared.SurveyPermissions;
import dev.ngb.survey.support.RepositoryPaths;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The committed contract must equal what the code generates. After an intended API change run
 * {@code ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true} and commit the result.
 *
 * <p>The application serves the tenant API under {@code /api/v1} and the public fill under {@code /p} after the proxy
 * strips the survey prefix, so the published document carries that prefix in its server URL.
 */
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest extends SurveyIntegrationTest {

    private static final String CONTRACT = "contracts/openapi/survey.yaml";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Autowired MockMvc mvc;

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        CallerLabelRules.check(generated, false);
        CallerOpenApiSplitter.writeOrVerify(
                generated,
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi"),
                "survey",
                Boolean.getBoolean("openapi.update"));

        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
        }

        assertThat(file).as(CONTRACT + " is missing; regenerate it with -Dopenapi.update=true").exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as(CONTRACT + " is out of date; regenerate it with -Dopenapi.update=true and commit the result")
                .isEqualTo(generated);
    }

    @Test
    void everyOperationNamesACataloguedPermissionOrIsPublic() throws Exception {
        JsonNode root = YAML.readTree(Files.readString(
                RepositoryPaths.repositoryRoot().resolve(CONTRACT), StandardCharsets.UTF_8));
        Set<String> seen = new HashSet<>();
        for (String path : root.get("paths").propertyNames()) {
            JsonNode operations = root.get("paths").get(path);
            for (String method : operations.propertyNames()) {
                JsonNode operation = operations.get(method);
                if (!operation.isObject()) {
                    continue;
                }
                String id = operation.get("operationId") == null ? null : operation.get("operationId").asString();
                assertThat(id).as(path + " " + method + " has no operationId").isNotBlank();
                assertThat(seen.add(id)).as("operationId " + id + " appears twice").isTrue();
                JsonNode permission = operation.get("x-required-permission");
                boolean labelled = operation.get("x-allowed-callers") != null;
                assertThat(permission != null || labelled)
                        .as(path + " " + method + " names neither a permission nor a caller").isTrue();
                if (permission != null) {
                    assertThat(SurveyPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
    }

    @Test
    void scalarReferenceIsServedWithoutATokenAndReadsTheGeneratedDocument() throws Exception {
        var response = mvc.perform(get("/scalar")).andReturn().getResponse();
        String page = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(page).contains("/scalar/scalar.js").contains("/v3/api-docs").contains("\"telemetry\":false");
    }
}
