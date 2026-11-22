package dev.ngb.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.openapi.CallerLabelRules;
import dev.ngb.core.openapi.CallerOpenApiSplitter;
import dev.ngb.notification.Application;
import dev.ngb.notification.support.PostgresContainerConfig;
import dev.ngb.notification.support.RepositoryPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String CONTRACT = "contracts/openapi/notification.yaml";
    private static final String PLATFORM_HOST = "platform.localhost";

    @Autowired MockMvc mvc;
    @Autowired DSLContext dsl;

    @Test
    void committedOpenApiDocumentMatchesTheGeneratedOne() throws Exception {
        seedPlatformDomain();
        String generated = mvc.perform(get("/v3/api-docs.yaml").header("Host", PLATFORM_HOST))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).startsWith("openapi:");
        CallerLabelRules.check(generated, false);
        CallerOpenApiSplitter.writeOrVerify(
                generated,
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi"),
                "notification",
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

    private void seedPlatformDomain() {
        dsl.execute(
                """
                insert into tenant_domains (id, tenant_id, hostname, kind, created_at, version)
                values (?, null, ?, 'PLATFORM', now(), 1)
                on conflict (hostname) do nothing
                """,
                UUID.randomUUID(),
                PLATFORM_HOST);
        assertThat(dsl.fetchCount(dsl.selectOne().from("tenant_domains"))).isGreaterThan(0);
    }

    @Test
    void scalarReferenceIsServedWithoutATokenAndReadsTheGeneratedDocument() throws Exception {
        seedPlatformDomain();
        var response = mvc.perform(get("/scalar").header("Host", PLATFORM_HOST)).andReturn().getResponse();
        String page = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(page).contains("/scalar/scalar.js").contains("/v3/api-docs").contains("\"telemetry\":false");
    }
}
