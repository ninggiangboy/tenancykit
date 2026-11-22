package dev.ngb.workflow.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.workflow.Application;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.support.PostgresContainerConfig;
import dev.ngb.workflow.support.RabbitContainerConfig;
import dev.ngb.workflow.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The committed contract must equal what the code generates. After an intended API change run
 * {@code ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true} and commit the result.
 *
 * <p>The application serves the tenant API under {@code /api/v1} after the proxy strips its prefix, so the published
 * document carries that prefix in its server URL and drops the prefixed tracking aliases the ALB forwards.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("api")
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/workflow.yaml";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("workflow.jobs.enabled", () -> "false");
        registry.add("workflow.secrets.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        String published = publishedDocument(generated);

        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, published, StandardCharsets.UTF_8);
        }

        assertThat(file).as(CONTRACT + " is missing; regenerate it with -Dopenapi.update=true").exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as(CONTRACT + " is out of date; regenerate it with -Dopenapi.update=true and commit the result")
                .isEqualTo(published);
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
                boolean publicly = operation.path("x-public").asBoolean(false);
                assertThat(permission != null || publicly)
                        .as(path + " " + method + " names neither a permission nor is public").isTrue();
                if (permission != null) {
                    assertThat(WorkflowPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
    }

    /** Removes the prefixed tracking aliases; the server URL already carries the workflow prefix. */
    private static String publishedDocument(String generated) {
        ObjectNode root = (ObjectNode) YAML.readTree(generated);
        ObjectNode paths = (ObjectNode) root.get("paths");
        for (String path : new ArrayList<>(paths.propertyNames())) {
            if (path.startsWith("/workflow/")) {
                paths.remove(path);
            }
        }
        return YAML.writeValueAsString(root);
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
