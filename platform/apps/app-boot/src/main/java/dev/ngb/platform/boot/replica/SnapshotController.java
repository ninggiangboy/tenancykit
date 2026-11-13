package dev.ngb.platform.boot.replica;

import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.access.api.user.UserAccessView;
import dev.ngb.platform.access.api.user.UserQueries;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemQueries;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemView;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
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
    private final UserQueries users;
    private final TenantSystemQueries tenantSystems;

    SnapshotController(
            TenantQueries tenants, EntitlementQueries entitlements, UserQueries users, TenantSystemQueries tenantSystems) {
        this.tenants = tenants;
        this.entitlements = entitlements;
        this.users = users;
        this.tenantSystems = tenantSystems;
    }

    @GetMapping("/internal/replica/{stream}")
    @RequiresPermission(PlatformPermissions.REPLICA_READ)
    Map<String, Object> page(
            @PathVariable String stream,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "100") int limit) {
        int size = Math.clamp(limit, 1, 500);
        return switch (replicaStream(stream)) {
            case TENANTS -> pageOf(tenants.page(after, size), size, SnapshotController::tenantItem);
            case TENANT_DOMAINS -> pageOf(tenants.pageDomains(after, size), size, SnapshotController::domainItem);
            case TENANT_SYSTEMS -> pageOf(tenantSystems.page(after, size), size, SnapshotController::tenantSystemItem);
            case ENTITLEMENTS -> entitlementPage(entitlements.page(after, size), size);
            case USER_ACCESS -> pageOf(users.pageAccess(after, size), size, SnapshotController::userAccessItem);
        };
    }

    /** The stream the path names, from the replica's one list of streams, so a stream cannot exist in only one place. */
    private static ReplicaStream replicaStream(String stream) {
        return ReplicaStream.byStreamName(stream)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown replica stream"));
    }

    /** The cursor is the last tenant id, and a page holds every row of up to {@code size} tenants. */
    private static Map<String, Object> entitlementPage(List<EntitlementView> rows, int size) {
        long tenantsOnPage = rows.stream().map(EntitlementView::tenantId).distinct().count();
        return pageOf(rows, tenantsOnPage == size, SnapshotController::entitlementItem);
    }

    private static <T> Map<String, Object> pageOf(List<T> rows, int requested, ItemMapper<T> mapper) {
        return pageOf(rows, rows.size() == requested, mapper);
    }

    private static <T> Map<String, Object> pageOf(List<T> rows, boolean full, ItemMapper<T> mapper) {
        List<Map<String, Object>> items = new ArrayList<>();
        UUID last = null;
        for (T row : rows) {
            Map<String, Object> item = mapper.item(row);
            items.add(item);
            Object cursor = item.get("id") != null ? item.get("id") : item.get("user_id") != null ? item.get("user_id") : item.get("tenant_id");
            last = (UUID) cursor;
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("items", items);
        answer.put("next", full ? last : null);
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

    private static Map<String, Object> tenantSystemItem(TenantSystemView system) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", system.id());
        item.put("tenant_id", system.tenantId());
        item.put("name", system.name());
        item.put("status", system.status());
        item.put("created_at", system.createdAt().toString());
        item.put("version", system.version());
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

    private static Map<String, Object> userAccessItem(UserAccessView access) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("user_id", access.user().id());
        item.put("tenant_id", access.user().tenantId());
        item.put("scope", access.user().scope());
        item.put("keycloak_subject", access.user().keycloakSubject());
        item.put("email", access.user().email());
        item.put("display_name", access.user().displayName());
        item.put("status", access.user().status());
        item.put("permissions", access.sortedPermissions());
        item.put("version", access.user().version());
        return item;
    }

    @FunctionalInterface
    private interface ItemMapper<T> {
        Map<String, Object> item(T row);
    }
}
