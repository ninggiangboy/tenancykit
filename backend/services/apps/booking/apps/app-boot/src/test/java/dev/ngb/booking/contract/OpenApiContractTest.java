package dev.ngb.booking.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.booking.Application;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.PostgresContainerConfig;
import dev.ngb.booking.support.RabbitContainerConfig;
import dev.ngb.booking.support.RepositoryPaths;
import dev.ngb.core.openapi.CallerLabelRules;
import dev.ngb.core.openapi.CallerOpenApiSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * The committed contract documents every operation booking serves, with the same permission, and that permission is in
 * the catalog. The contract is written ahead of the phases, so the comparison runs in the direction that is true at
 * every phase: a served operation must be documented, not the other way around.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("api")
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/booking.yaml";

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Autowired
    MockMvc mvc;

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
        registry.add("booking.jobs.enabled", () -> "false");
        // The webhook secret cipher refuses to start without a 32-byte key.
        registry.add("booking.webhooks.master-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("booking.gate.keys.k1", () -> "test-customer-token-secret");
        registry.add("booking.housekeeping.enabled", () -> "false");
    }

    @Test
    void everyServedOperationIsDocumentedWithACataloguedPermission() throws Exception {
        JsonNode served = YAML.readTree(mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(served.get("openapi")).as("the generated document is readable").isNotNull();

        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        String documentedText = Files.readString(file, StandardCharsets.UTF_8);
        CallerLabelRules.check(served.toString(), false);
        CallerLabelRules.check(documentedText, false);
        CallerOpenApiSplitter.writeOrVerify(
                documentedText,
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi"),
                "booking",
                Boolean.getBoolean("openapi.update"));
        JsonNode documented = YAML.readTree(documentedText);

        int operations = 0;
        Set<String> operationIds = new TreeSet<>();
        Map<String, String> permissionByOperation = new LinkedHashMap<>();
        for (String path : served.get("paths").propertyNames()) {
            JsonNode servedPath = served.get("paths").get(path);
            for (String method : servedPath.propertyNames()) {
                JsonNode operation = servedPath.get(method);
                if (!operation.isObject()) {
                    continue;
                }
                JsonNode documentedOperation = documented.path("paths").path(path).path(method);
                assertThat(documentedOperation.isObject())
                        .as(method.toUpperCase() + " " + path + " is served but " + CONTRACT + " does not document it")
                        .isTrue();
                assertThat(documentedOperation.get("x-allowed-callers"))
                        .as(method.toUpperCase() + " " + path + " documents another caller than the app derives")
                        .isEqualTo(operation.get("x-allowed-callers"));
                assertThat(documentedOperation.path("description").asString(""))
                        .as(method.toUpperCase() + " " + path + " documents another callers line than the app derives")
                        .contains(callersLine(operation));
                if (path.startsWith("/p/")) {
                    // The public gate authenticates with a customer token, not a suite permission.
                    continue;
                }
                operations++;
                String id = operation.get("operationId") == null ? null : operation.get("operationId").asString();
                assertThat(id).as(path + " " + method + " has no operationId").isNotBlank();
                assertThat(operationIds.add(id)).as("operationId " + id + " appears twice").isTrue();

                JsonNode permission = operation.get("x-required-permission");
                assertThat(permission).as(path + " " + method + " declares no permission").isNotNull();
                assertThat(BookingPermissions.CODES)
                        .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                        .contains(permission.asString());
                assertThat(documentedOperation.path("x-required-permission").asString())
                        .as(method.toUpperCase() + " " + path + " documents another permission than the app enforces")
                        .isEqualTo(permission.asString());
                permissionByOperation.put(method.toUpperCase() + " " + path, permission.asString());
            }
        }
        assertThat(operations).as("a scan that finds no operation would pass vacuously").isGreaterThan(0);
        assertThat(permissionByOperation.values()).isSubsetOf(BookingPermissions.CODES);
    }

    private static String callersLine(JsonNode operation) {
        String description = operation.path("description").asString("");
        int at = description.lastIndexOf("\n\n");
        String line = at < 0 ? description : description.substring(at + 2);
        assertThat(line).startsWith("Callers:");
        return line;
    }

    /** What the catalog phase serves: resources, their hours and exceptions, groups and offerings. */
    @Test
    void theServedSurfaceIsTheCatalogSurface() throws Exception {
        JsonNode served = YAML.readTree(mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        Set<String> servedPaths = new TreeSet<>(Set.of(served.get("paths").propertyNames().toArray(String[]::new)));
        assertThat(servedPaths).contains(
                "/api/v1/resources",
                "/api/v1/resources/{resourceId}",
                "/api/v1/resources/{resourceId}/working-hours",
                "/api/v1/resources/{resourceId}/exceptions",
                "/api/v1/resource-groups",
                "/api/v1/resource-groups/{groupId}",
                "/api/v1/offerings",
                "/api/v1/offerings/{offeringId}",
                "/api/v1/offerings/{offeringId}/requirements",
                "/api/v1/offerings/{offeringId}/cancellation-windows");
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
