package dev.ngb.platform.access.adapter.catalog.yaml;

import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.domain.permission.CataloguedPermission;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The suite's catalogs, packaged from {@code contracts/permissions} under {@code permissions/}: one YAML list per
 * app. Duplicate codes or unknown scopes fail at startup, because a catalog that cannot be trusted must not run.
 */
public class YamlPermissionCatalog implements PermissionCatalog {

    private static final String SUFFIX = ".yaml";
    private static final TypeReference<List<Map<String, Object>>> ENTRIES = new TypeReference<>() {};

    private final Map<String, CataloguedPermission> byCode;

    public YamlPermissionCatalog() {
        YAMLMapper yaml = YAMLMapper.builder().build();
        Map<String, CataloguedPermission> found = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:permissions/*" + SUFFIX)) {
                for (Map<String, Object> entry : yaml.readValue(resource.getInputStream(), ENTRIES)) {
                    CataloguedPermission permission = toPermission(entry);
                    if (found.putIfAbsent(permission.code(), permission) != null) {
                        throw new IllegalStateException("duplicate permission code in the catalogs: " + permission.code());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the permission catalogs", e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no permission catalogs were found under permissions/");
        }
        this.byCode = Map.copyOf(found);
    }

    @Override
    public Optional<CataloguedPermission> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    @Override
    public List<CataloguedPermission> all() {
        return List.copyOf(byCode.values());
    }

    @Override
    public Set<String> codesValidIn(PermissionScope scope) {
        Set<String> codes = new TreeSet<>();
        byCode.values().stream().filter(permission -> permission.validIn(scope)).forEach(permission -> codes.add(permission.code()));
        return Set.copyOf(codes);
    }

    private static CataloguedPermission toPermission(Map<String, Object> entry) {
        Object code = entry.get("code");
        Object description = entry.get("description");
        Object scopes = entry.get("scopes");
        Object grantable = entry.get("grantable_to_tenant_system");
        if (code == null || description == null || !(scopes instanceof List<?> scopeValues) || grantable == null) {
            throw new IllegalStateException("a permission entry is missing code, description, scopes or grantable_to_tenant_system: " + entry);
        }
        List<PermissionScope> parsed = new ArrayList<>();
        for (Object scope : scopeValues) {
            parsed.add(PermissionScope.valueOf(String.valueOf(scope).toUpperCase(java.util.Locale.ROOT)));
        }
        return new CataloguedPermission(
                String.valueOf(code), String.valueOf(description), Set.copyOf(parsed), Boolean.parseBoolean(String.valueOf(grantable)));
    }
}
