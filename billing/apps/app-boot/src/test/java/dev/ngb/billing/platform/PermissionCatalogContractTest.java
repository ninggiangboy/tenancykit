package dev.ngb.billing.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.platform.api.actor.Permission;
import dev.ngb.billing.support.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every code in Billing's {@link Permission} enum has an entry in one of the suite's catalogs under its namespaced
 * name. Removing a catalog entry, renaming a code or forgetting the prefix fails here, so the catalogs and the code
 * cannot drift apart while the enum still exists (it is deleted in the Billing cutover).
 */
class PermissionCatalogContractTest {

    private static final Pattern CODE = Pattern.compile("^\\s*-\\s*code:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /** The Billing codes that belong to Platform, matched by the resource prefix they share. */
    private static final List<String> PLATFORM_PREFIXES =
            List.of("tenant:", "tenant-domain:", "user:", "role:", "tenant-system:", "credential:");

    @Test
    void everyPermissionCodeHasItsNamespacedCatalogEntry() {
        Map<String, Set<String>> catalogs = Map.of(
                "platform", codes("platform.yaml"),
                "billing", codes("billing.yaml"),
                "notification", codes("notification.yaml"));

        for (Permission permission : Permission.values()) {
            String app = appOf(permission.code());
            assertThat(catalogs.get(app))
                    .as("%s must be catalogued as %s:%s", permission, app, permission.code())
                    .contains(app + ":" + permission.code());
        }
    }

    private static String appOf(String code) {
        if (code.startsWith("webhook:")) {
            return "notification";
        }
        return PLATFORM_PREFIXES.stream().anyMatch(code::startsWith) ? "platform" : "billing";
    }

    private static Set<String> codes(String file) {
        Path path = RepositoryPaths.repositoryRoot().resolve("contracts/permissions").resolve(file);
        try {
            Set<String> found = new LinkedHashSet<>();
            Matcher matcher = CODE.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
            return found;
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
