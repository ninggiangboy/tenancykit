package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class CallerOpenApiSplitterTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final String DOCUMENT = """
            openapi: 3.1.0
            info:
              title: Demo
              version: v1
            paths:
              /api/v1/wallets:
                get:
                  operationId: listWallets
                  x-allowed-callers:
                  - tenant-user
              /system-only:
                get:
                  operationId: systemRead
                  x-allowed-callers:
                  - tenant-system
              /shared:
                get:
                  operationId: sharedRead
                  x-allowed-callers:
                  - tenant-user
                  - tenant-system
              /public/{token}:
                post:
                  operationId: open
                  x-allowed-callers:
                  - public
            """;

    private static JsonNode parsed() {
        return YAML.readTree(DOCUMENT);
    }

    @Test
    void oneCallerGetsOnlyItsOperationsAndTheSharedPathKeepsItsLabel() {
        JsonNode tenant = YAML.readTree(CallerOpenApiSplitter.split(parsed(), "tenant-user"));

        assertThat(tenant.get("paths").propertyNames()).containsExactly("/api/v1/wallets", "/shared");
        assertThat(tenant.get("paths").get("/shared").get("get").get("x-allowed-callers").toString())
                .isEqualTo("[\"tenant-user\",\"tenant-system\"]");
    }

    @Test
    void theSystemAndPublicFilesAreSeparate() {
        JsonNode system = YAML.readTree(CallerOpenApiSplitter.split(parsed(), "tenant-system"));
        JsonNode open = YAML.readTree(CallerOpenApiSplitter.split(parsed(), "public"));

        assertThat(system.get("paths").propertyNames()).containsExactly("/system-only", "/shared");
        assertThat(open.get("paths").propertyNames()).containsExactly("/public/{token}");
    }

    @Test
    void aCallerWithNoOperationGetsNoDocument() {
        assertThat(CallerOpenApiSplitter.split(parsed(), "provider")).isNull();
    }

    @Test
    void writingVerifyingAndRemovingStaleFiles(@TempDir Path root) throws Exception {
        CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", true);

        assertThat(root.resolve("tenant-user/demo.yaml")).exists();
        assertThat(root.resolve("tenant-system/demo.yaml")).exists();
        assertThat(root.resolve("public/demo.yaml")).exists();
        assertThat(root.resolve("provider/demo.yaml")).doesNotExist();
        CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", false);

        Path stale = root.resolve("provider/demo.yaml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "openapi: 3.1.0\npaths: {}\n");
        assertThatThrownBy(() -> CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("stale per-caller file");
        CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", true);
        assertThat(stale).doesNotExist();
    }

    @Test
    void aChangedFileFailsVerification(@TempDir Path root) throws Exception {
        CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", true);
        Path file = root.resolve("public/demo.yaml");
        Files.writeString(file, Document.headerOnly());

        assertThatThrownBy(() -> CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("out of date");
    }

    @Test
    void aMissingFileFailsVerification(@TempDir Path root) {
        assertThatThrownBy(() -> CallerOpenApiSplitter.writeOrVerify(DOCUMENT, root, "demo", false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing per-caller file");
    }

    private static final class Document {
        static String headerOnly() {
            return "openapi: 3.1.0\ninfo:\n  title: Demo\n  version: v1\npaths: {}\n";
        }
    }
}
