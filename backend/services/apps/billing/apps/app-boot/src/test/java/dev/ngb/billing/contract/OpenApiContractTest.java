package dev.ngb.billing.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.billing.Application;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ReplicaFixtures;
import dev.ngb.billing.support.RepositoryPaths;
import dev.ngb.core.openapi.CallerLabelRules;
import dev.ngb.core.openapi.CallerOpenApiSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed contract must equal what the code generates. After an intended API change run
 * {@code ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true} and commit the result.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class OpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/billing.yaml";

    @Autowired MockMvc mvc;
    @Autowired ReplicaFixtures replica;
    @Value("${billing.tenancy.base-domain}") String baseDomain;

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        // The API now answers on a tenant hostname; the platform host belongs to the Platform service.
        String host = replica.tenant().host();
        String generated = mvc.perform(get("/v3/api-docs.yaml").header("Host", host))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        CallerLabelRules.check(generated, false);
        CallerOpenApiSplitter.writeOrVerify(
                generated,
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi"),
                "billing",
                Boolean.getBoolean("openapi.update"));
        Path root = repositoryRoot();
        Path file = root.resolve(CONTRACT);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
        }

        assertThat(file).as(CONTRACT + " is missing; regenerate it with -Dopenapi.update=true").exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as(CONTRACT + " is out of date; regenerate it with -Dopenapi.update=true and commit the result")
                .isEqualTo(generated);
    }

    private static Path repositoryRoot() {
        return RepositoryPaths.repositoryRoot();
    }

    @Test
    void scalarReferenceIsServedWithoutATokenAndReadsTheGeneratedDocument() throws Exception {
        var response = mvc.perform(get("/scalar").header("Host", replica.tenant().host())).andReturn().getResponse();
        String page = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(page).contains("/scalar/scalar.js").contains("/v3/api-docs").contains("\"telemetry\":false");
    }
}
