package dev.ngb.platform.boot.replica;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementQueries;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantDomainView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The snapshot a replica is rebuilt from. Page with {@code after} set to the previous answer's {@code next}; the
 * items carry the same JSON as the matching {@code *.changed} event plus {@code version}, so a replica can merge
 * snapshot rows and live events in any order.
 */
@RestController
class SnapshotController {

    private final TenantQueries tenants;
    private final EntitlementQueries entitlements;

    SnapshotController(TenantQueries tenants, EntitlementQueries entitlements) {
        this.tenants = tenants;
        this.entitlements = entitlements;
    }

    @GetMapping("/internal/replica/{stream}")
    @RequiresPermission("platform:replica:read")
    Map<String, Object> page(
            @PathVariable String stream,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "100") int limit) {
        int size = Math.clamp(limit, 1, 500);
        return switch (stream) {
            case "tenants" -> pageOf(tenants.page(after, size), size, SnapshotController::tenantItem);
            case "tenant-domains" -> pageOf(tenants.pageDomains(after, size), size, SnapshotController::domainItem);
            case "entitlements" -> pageOf(entitlements.page(after, size), size, SnapshotController::entitlementItem);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown replica stream");
        };
    }

    private static <T> Map<String, Object> pageOf(List<T> rows, int requested, ItemMapper<T> mapper) {
        List<Map<String, Object>> items = new ArrayList<>();
        UUID last = null;
        for (T row : rows) {
            Map<String, Object> item = mapper.item(row);
            items.add(item);
            last = item.containsKey("id") ? (UUID) item.get("id") : (UUID) item.get("tenant_id");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("items", items);
        answer.put("next", rows.size() == requested ? last : null);
        return answer;
    }

    private static Map<String, Object> tenantItem(TenantView tenant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", tenant.id());
        item.put("code", tenant.code());
        item.put("name", tenant.name());
        item.put("status", tenant.status());
        item.put("created_at", tenant.createdAt().toString());
        item.put("version", tenant.version());
        return item;
    }

    private static Map<String, Object> domainItem(TenantDomainView domain) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", domain.id());
        item.put("tenant_id", domain.tenantId());
        item.put("hostname", domain.hostname());
        item.put("kind", domain.kind());
        item.put("created_at", domain.createdAt().toString());
        item.put("version", domain.version());
        return item;
    }

    private static Map<String, Object> entitlementItem(EntitlementView entitlement) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tenant_id", entitlement.tenantId());
        item.put("app_code", entitlement.appCode());
        item.put("enabled", entitlement.enabled());
        item.put("version", entitlement.version());
        return item;
    }

    @FunctionalInterface
    private interface ItemMapper<T> {
        Map<String, Object> item(T row);
    }
}
