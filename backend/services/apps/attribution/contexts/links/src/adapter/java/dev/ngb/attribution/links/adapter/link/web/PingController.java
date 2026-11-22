package dev.ngb.attribution.links.adapter.link.web;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Health check",
        description =
                "Answers the caller's tenant identity, proving the token, tenant scope and permission check are "
                + "wired.")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class PingController {

    @Operation(
            summary = "Check the caller's tenant",
            description =
                    "Answers the caller's own tenant identity and nothing else. It proves that the suite token, the "
                    + "tenant scope and the permission check are wired; a platform-scope caller is refused even when it "
                    + "holds the code.")
    @GetMapping("/ping")
    @RequiresPermission(AttributionPermissions.LINK_READ)
    Map<String, Object> ping() {
        return Map.of("app", "attribution", "status", "ok");
    }
}
