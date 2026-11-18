package dev.ngb.loyalty.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import dev.ngb.loyalty.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The direction is one way in this phase: the committed document describes the whole app, most of which does not exist
 * yet, so every operation the running app serves must be documented and must name a catalogued permission — but a
 * documented operation the app does not serve yet is not a failure. A later phase tightens this to equality once the
 * last operation is served.
 */
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest extends LoyaltyIntegrationTest {

    private static final String CONTRACT = "contracts/openapi/loyalty.yaml";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Autowired MockMvc mvc;

    @Test
    void everyServedOperationIsDocumented() throws Exception {
        JsonNode generated = generatedDocument();
        JsonNode published = YAML.readTree(Files.readString(RepositoryPaths.repositoryRoot().resolve(CONTRACT), StandardCharsets.UTF_8));

        List<String> undocumented = new ArrayList<>();
        for (String path : generated.get("paths").propertyNames()) {
            JsonNode served = generated.get("paths").get(path);
            JsonNode documented = published.get("paths").get(path);
            for (String method : served.propertyNames()) {
                if (!served.get(method).isObject()) {
                    continue;
                }
                if (documented == null || documented.get(method) == null || !documented.get(method).isObject()) {
                    undocumented.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
                }
            }
        }
        assertThat(undocumented)
                .as("an operation the app serves must be in " + CONTRACT)
                .isEmpty();
    }

    @Test
    void everyServedOperationNamesACataloguedPermission() throws Exception {
        JsonNode generated = generatedDocument();
        Set<String> seen = new HashSet<>();
        int served = 0;
        for (String path : generated.get("paths").propertyNames()) {
            JsonNode operations = generated.get("paths").get(path);
            for (String method : operations.propertyNames()) {
                JsonNode operation = operations.get(method);
                if (!operation.isObject()) {
                    continue;
                }
                served++;
                String id = operation.get("operationId") == null ? null : operation.get("operationId").asString();
                assertThat(id).as(path + " " + method + " has no operationId").isNotBlank();
                assertThat(seen.add(id)).as("operationId " + id + " appears twice").isTrue();
                JsonNode permission = operation.get("x-required-permission");
                boolean publicly = operation.path("x-public").asBoolean(false);
                assertThat(permission != null || publicly)
                        .as(path + " " + method + " names neither a permission nor is public").isTrue();
                if (permission != null) {
                    assertThat(LoyaltyPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
        assertThat(served).as("the app serves the webhook endpoints").isGreaterThanOrEqualTo(6);
    }

    /** The document is published under the prefix the proxy strips, so its server URL carries `/loyalty`. */
    @Test
    void theDocumentedServerCarriesTheAppPrefix() throws Exception {
        JsonNode generated = generatedDocument();
        assertThat(generated.get("servers").get(0).get("url").asString()).isEqualTo("/loyalty");
        assertThat(generated.get("info").get("title").asString()).isEqualTo("Loyalty API");
    }

    private JsonNode generatedDocument() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        return YAML.readTree(generated);
    }

    private static Path contract() {
        return RepositoryPaths.repositoryRoot().resolve(CONTRACT);
    }
}
