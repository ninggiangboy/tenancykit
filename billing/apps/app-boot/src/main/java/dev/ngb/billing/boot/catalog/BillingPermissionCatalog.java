package dev.ngb.billing.boot.catalog;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Billing's view of the permission catalogs packaged under {@code permissions/}. It answers the questions the
 * OpenAPI customizers ask: whether a code applies in platform or tenant scope, and whether a Tenant System may hold
 * it. The catalogs under {@code contracts/permissions} stay the single source of truth.
 */
public class BillingPermissionCatalog {

    /** One catalog entry. */
    public record Entry(String code, Set<String> scopes, boolean grantableToTenantSystem) {}

    private static final TypeReference<List<Map<String, Object>>> ENTRIES = new TypeReference<>() {};

    private final Map<String, Entry> byCode;

    public BillingPermissionCatalog() {
        YAMLMapper yaml = YAMLMapper.builder().build();
        Map<String, Entry> found = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:permissions/*.yaml")) {
                for (Map<String, Object> entry : yaml.readValue(resource.getInputStream(), ENTRIES)) {
                    found.putIfAbsent(String.valueOf(entry.get("code")), toEntry(entry));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the permission catalogs", e);
        }
        this.byCode = Map.copyOf(found);
    }

    public Optional<Entry> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** Every code in the catalogs, so a test can prove the constants and the catalog agree. */
    public Set<String> codes() {
        return byCode.keySet();
    }

    private static Entry toEntry(Map<String, Object> entry) {
        Object scopes = entry.get("scopes");
        Set<String> parsed = scopes instanceof List<?> values
                ? values.stream().map(value -> String.valueOf(value).toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        return new Entry(
                String.valueOf(entry.get("code")),
                parsed,
                Boolean.parseBoolean(String.valueOf(entry.get("grantable_to_tenant_system"))));
    }
}
