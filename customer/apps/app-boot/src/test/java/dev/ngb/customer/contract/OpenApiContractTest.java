package dev.ngb.customer.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.customer.Application;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.PostgresContainerConfig;
import dev.ngb.customer.support.RabbitContainerConfig;
import dev.ngb.customer.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The committed contract documents every operation customer serves, and the test runs the comparison in the direction that
 * is true today: every operation the app actually serves must be documented, with the same permission, and that
 * permission must exist in the catalog. The documented operations customer does not serve yet are the other direction; the
 * surface test below names what exists, so a served endpoint cannot appear undocumented and a documented one cannot be
 * forgotten once it is built.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("api")
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/customer.yaml";
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
        registry.add("customer.jobs.enabled", () -> "false");
        registry.add("customer.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void everyServedOperationIsDocumentedWithACataloguedPermission() throws Exception {
        JsonNode served = YAML.readTree(mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(served.get("openapi")).as("the generated document is readable").isNotNull();

        Path file = RepositoryPaths.repositoryRoot().resolve(CONTRACT);
        JsonNode documented = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));

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
                operations++;
                String id = operation.get("operationId") == null ? null : operation.get("operationId").asString();
                assertThat(id).as(path + " " + method + " has no operationId").isNotBlank();
                assertThat(operationIds.add(id)).as("operationId " + id + " appears twice").isTrue();

                JsonNode documentedOperation = documented.path("paths").path(path).path(method);
                assertThat(documentedOperation.isObject())
                        .as(method.toUpperCase() + " " + path + " is served but " + CONTRACT + " does not document it")
                        .isTrue();

                JsonNode permission = operation.get("x-required-permission");
                assertThat(permission).as(path + " " + method + " declares no permission").isNotNull();
                assertThat(CustomerPermissions.CODES)
                        .as(path + " " + method + " names " + permission.asString() + ", which is in no catalog")
                        .contains(permission.asString());
                assertThat(documentedOperation.path("x-required-permission").asString())
                        .as(method.toUpperCase() + " " + path + " documents another permission than the app enforces")
                        .isEqualTo(permission.asString());
                permissionByOperation.put(method.toUpperCase() + " " + path, permission.asString());
            }
        }
        assertThat(operations).as("a scan that finds no operation would pass vacuousley").isGreaterThan(0);
        assertThat(permissionByOperation.values()).isSubsetOf(CustomerPermissions.CODES);
    }

    /**
     * What customer serves today: the customer surface with the fields, tags and consent that belong to it, the orders with
     * their returns and one customer's order history, and the webhook surface. A new endpoint extends this set.
     */
    @Test
    void theServedSurfaceIsTheCustomerFieldsTagsConsentOrderAndWebhookSurface() throws Exception {
        JsonNode served = YAML.readTree(mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        Set<String> servedPaths = new TreeSet<>(List.of(served.get("paths").propertyNames().toArray(String[]::new)));
        assertThat(servedPaths).containsExactlyInAnyOrder(
                "/api/v1/activities",
                "/api/v1/activities/batch",
                "/api/v1/consent-topics",
                "/api/v1/consent-topics/{key}",
                "/api/v1/custom-fields",
                "/api/v1/custom-fields/{key}",
                "/api/v1/customers",
                "/api/v1/customers/batch",
                "/api/v1/customers/export",
                "/api/v1/customers/{externalRef}",
                "/api/v1/customers/{externalRef}/activities",
                "/api/v1/customers/{externalRef}/consents",
                "/api/v1/customers/{externalRef}/erasure",
                "/api/v1/customers/{externalRef}/orders",
                "/api/v1/customers/{externalRef}/profile",
                "/api/v1/orders/batch",
                "/api/v1/orders/{orderRef}",
                "/api/v1/orders/{orderRef}/returns/{returnRef}",
                "/api/v1/tags",
                "/api/v1/webhook-deliveries",
                "/api/v1/webhook-deliveries/{id}/retry",
                "/api/v1/webhook-endpoints",
                "/api/v1/webhook-endpoints/{id}",
                "/api/v1/webhook-endpoints/{id}/rotate-secret");
    }
}
