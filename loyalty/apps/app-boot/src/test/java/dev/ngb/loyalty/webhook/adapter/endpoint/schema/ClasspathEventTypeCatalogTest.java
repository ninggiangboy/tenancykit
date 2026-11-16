package dev.ngb.loyalty.webhook.adapter.endpoint.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The catalog an endpoint may subscribe to is the packaged contract: the twelve schemas the build copies from
 * {@code contracts/events/loyalty}, which are also the enum the published OpenAPI document lists. A type added to one
 * and not the others fails here rather than at a Tenant's first subscription.
 */
class ClasspathEventTypeCatalogTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final Set<String> catalog = new ClasspathEventTypeCatalog().eventTypes();

    @Test
    void theCatalogIsTheTwelvePackagedSchemas() throws IOException {
        assertThat(catalog).containsExactlyInAnyOrderElementsOf(schemaTypes());
        assertThat(catalog).hasSize(12);
    }

    @Test
    void theCatalogIsTheDocumentedEventTypeEnum() throws IOException {
        assertThat(catalog).containsExactlyInAnyOrderElementsOf(documentedTypes());
    }

    @Test
    void everyDocumentedPayloadCarriesTheMemberIdentity() throws IOException {
        Path directory = RepositoryPaths.repositoryRoot().resolve("contracts/events/loyalty");
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".schema.json")).toList()) {
                JsonNode payload = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("properties");
                assertThat(payload.has("memberId")).as(file.getFileName() + " must carry memberId").isTrue();
                if (!file.getFileName().toString().startsWith("member.erased")) {
                    assertThat(payload.has("externalRef"))
                            .as(file.getFileName() + " must carry externalRef; only member.erased leaves it out")
                            .isTrue();
                }
            }
        }
    }

    private static Set<String> schemaTypes() throws IOException {
        Path directory = RepositoryPaths.repositoryRoot().resolve("contracts/events/loyalty");
        Set<String> types = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".schema.json")) {
                    types.add(name.substring(0, name.length() - ".schema.json".length()));
                }
            }
        }
        return types;
    }

    private static Set<String> documentedTypes() throws IOException {
        JsonNode document = YAML.readTree(Files.readString(
                RepositoryPaths.repositoryRoot().resolve("contracts/openapi/loyalty.yaml"), StandardCharsets.UTF_8));
        Set<String> types = new TreeSet<>();
        document.get("components").get("schemas").get("EventType").get("enum")
                .forEach(node -> types.add(node.asString()));
        return types;
    }
}
