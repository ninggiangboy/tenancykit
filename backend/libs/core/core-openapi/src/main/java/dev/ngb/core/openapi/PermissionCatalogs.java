package dev.ngb.core.openapi;

import dev.ngb.core.security.PermissionScope;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The one reader of the permission catalogs packaged under {@code permissions/}. A service declares a bean from
 * {@link #fromClasspath()}; the label derivation asks it the two questions the catalog answers.
 *
 * <p>It reads every catalog on the classpath, not only the app's own, because Platform's catalog holds the internal
 * codes and a service may document one.
 */
public final class PermissionCatalogs implements CallerCatalog {

    private static final TypeReference<List<Map<String, Object>>> ENTRIES = new TypeReference<>() {};

    private record Entry(Set<PermissionScope> scopes, boolean grantableToTenantSystem) {}

    private final Map<String, Entry> byCode;

    private PermissionCatalogs(Map<String, Entry> byCode) {
        this.byCode = Map.copyOf(byCode);
    }

    /** Reads {@code classpath*:permissions/*.yaml}; a catalog that cannot be trusted fails the build that built it. */
    public static PermissionCatalogs fromClasspath() {
        YAMLMapper yaml = YAMLMapper.builder().build();
        Map<String, Entry> found = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:permissions/*.yaml");
            for (Resource resource : resources) {
                try (InputStream input = resource.getInputStream()) {
                    for (Map<String, Object> entry : yaml.readValue(input, ENTRIES)) {
                        String code = String.valueOf(entry.get("code"));
                        if (found.putIfAbsent(code, toEntry(entry)) != null) {
                            throw new IllegalStateException("duplicate permission code in the catalogs: " + code);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the permission catalogs", e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no permission catalogs were found under permissions/");
        }
        return new PermissionCatalogs(found);
    }

    /** Every catalogued code, so a test can prove the constants and the catalog agree. */
    public Set<String> codes() {
        return byCode.keySet();
    }

    @Override
    public boolean catalogues(String code) {
        return byCode.containsKey(code);
    }

    @Override
    public Set<PermissionScope> scopes(String code) {
        Entry entry = require(code);
        return entry.scopes();
    }

    @Override
    public boolean grantableToTenantSystem(String code) {
        return require(code).grantableToTenantSystem();
    }

    private Entry require(String code) {
        Entry entry = byCode.get(code);
        if (entry == null) {
            throw new IllegalStateException("the permission code is in no catalog: " + code);
        }
        return entry;
    }

    private static Entry toEntry(Map<String, Object> entry) {
        Object scopes = entry.get("scopes");
        Set<PermissionScope> parsed = scopes instanceof List<?> values
                ? values.stream()
                        .map(value -> PermissionScope.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT)))
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        return new Entry(parsed, Boolean.parseBoolean(String.valueOf(entry.get("grantable_to_tenant_system"))));
    }
}
