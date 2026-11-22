package dev.ngb.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.platform.Application;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed contract must equal what the code generates. After an intended API change run
 * {@code ./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest' -Dopenapi.update=true} and commit
 * contracts/openapi/platform.yaml.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class PlatformOpenApiContractTest {

    private static final String CONTRACT = "contracts/openapi/platform.yaml";

    @Autowired MockMvc mvc;

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

    @Test
    void scalarReferenceIsServedWithoutATokenAndReadsTheGeneratedDocument() throws Exception {
        var response = mvc.perform(get("/scalar")).andReturn().getResponse();
        String page = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(page).contains("/scalar/scalar.js").contains("/v3/api-docs").contains("\"telemetry\":false");
    }
}
