package dev.ngb.mailroom.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.openapi.CallerLabelRules;
import dev.ngb.core.openapi.CallerOpenApiSplitter;
import dev.ngb.mailroom.Application;
import dev.ngb.mailroom.shared.MailroomPermissions;
import dev.ngb.mailroom.support.PostgresContainerConfig;
import dev.ngb.mailroom.support.RabbitContainerConfig;
import dev.ngb.mailroom.support.RepositoryPaths;
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

    private static final String CONTRACT = "contracts/openapi/mailroom.yaml";
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
        registry.add("mailroom.jobs.enabled", () -> "false");
        registry.add("mailroom.tracking.keys.k1", () -> "test-tracking-secret");
    }

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        CallerLabelRules.check(generated, true, true);
        String published = publishedDocument(generated);
        CallerOpenApiSplitter.writeOrVerify(
                published,
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi"),
                "mailroom",
                Boolean.getBoolean("openapi.update"));

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
                boolean labelled = operation.get("x-allowed-callers") != null;
                assertThat(permission != null || labelled)
                        .as(path + " " + method + " names neither a permission nor a caller").isTrue();
                if (permission != null) {
                    assertThat(MailroomPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
    }

    /** Removes the prefixed tracking aliases; the server URL already carries the mailroom prefix. */
    private static String publishedDocument(String generated) {
        ObjectNode root = (ObjectNode) YAML.readTree(generated);
        ObjectNode paths = (ObjectNode) root.get("paths");
        for (String path : new ArrayList<>(paths.propertyNames())) {
            if (path.startsWith("/mailroom/")) {
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
