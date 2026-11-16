package dev.ngb.booking.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The permission constants and the catalog file are one set.
 *
 * <p>As the feature phases add controllers, this test also gains the two link assertions — every catalog code is
 * enforced by some endpoint, and every endpoint declares a permission, an authentic scope or public intent — because
 * both are vacuous while the surface is empty.
 */
class PermissionCatalogTest {

    @Test
    void theConstantsAndTheCatalogAreOneSet() throws IOException {
        assertThat(BookingPermissions.CODES).containsExactlyInAnyOrderElementsOf(catalogCodes());
    }

    private static Set<String> catalogCodes() throws IOException {
        JsonNode entries = YAMLMapper.builder()
                .build()
                .readTree(Files.readString(permissionsFile(), StandardCharsets.UTF_8));
        Set<String> codes = new TreeSet<>();
        entries.valueStream().map(entry -> entry.get("code").asString()).forEach(codes::add);
        return codes;
    }

    private static java.nio.file.Path permissionsFile() {
        return RepositoryPaths.repositoryRoot().resolve("contracts/permissions/booking.yaml");
    }
}
