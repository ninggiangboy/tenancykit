package dev.ngb.attribution.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.attribution.Application;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.support.RabbitContainerConfig;
import dev.ngb.attribution.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The committed contract must equal what the code generates. After an intended API change run
 * {@code ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true} and commit the result.
 *
 * <p>The application serves the tenant API under {@code /api/v1}, the redirect under {@code /go} and the collector
 * under {@code /t} after the proxy strips the attribution prefix, so the published document carries that prefix in its
 * server URL.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("api")
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/attribution.yaml";
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
        registry.add("attribution.jobs.enabled", () -> "false");
        registry.add("attribution.housekeeping.enabled", () -> "false");
        registry.add("attribution.keys.visitor-hash-key", () -> "test-visitor-hash-secret");
        registry.add("attribution.keys.click-keys.k1", () -> "test-click-secret");
    }

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");

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

    /** Every operation declares a permission or is explicitly public, so no operation is open by accident. */
    @Test
    void everyOperationDeclaresAPermissionOrIsPublic() throws Exception {
        JsonNode root = contract();
        Set<String> seen = new HashSet<>();
        Set<String> unmarked = new TreeSet<>();
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
                if (permission == null && !operation.path("x-public").asBoolean(false)) {
                    unmarked.add(method.toUpperCase() + " " + path);
                }
                if (permission != null) {
                    assertThat(AttributionPermissions.CODES)
                            .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                            .contains(permission.asString());
                }
            }
        }
        assertThat(unmarked).as("an operation must name a permission or be marked x-public").isEmpty();
    }

    private static JsonNode contract() {
        try {
            return YAML.readTree(Files.readString(
                    RepositoryPaths.repositoryRoot().resolve(CONTRACT), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
