package dev.ngb.attribution.links.adapter.link.web;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.security.RequiresPermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated surface's first endpoint, which proves the skeleton wires a suite token, the tenant scope and the
 * permission interceptor before any feature exists.
 *
 * <p>It answers one tenant's own identity and nothing else. A platform-scope caller is refused by the tenant-only guard
 * even though it holds the code, and a token without {@code attribution:link:read} is refused by the interceptor.
 */
@RestController
@RequestMapping("/api/v1")
class PingController {

    @GetMapping("/ping")
    @RequiresPermission(AttributionPermissions.LINK_READ)
    Map<String, Object> ping() {
        return Map.of("app", "attribution", "status", "ok");
    }
}
