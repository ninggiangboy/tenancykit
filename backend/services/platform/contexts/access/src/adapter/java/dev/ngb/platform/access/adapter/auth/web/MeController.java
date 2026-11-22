package dev.ngb.platform.access.adapter.auth.web;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.platform.access.api.user.MeQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The console reads the signed-in user and what they may do across the suite from here. */
@Tag(
        name = "Current user",
        description =
                "The signed-in user and the permissions they hold across the suite.")
@RestController
@Callers({Caller.PLATFORM_USER, Caller.TENANT_USER})
class MeController {

    record MeResponse(
            UUID id, UUID tenantId, String scope, String email, String displayName, String status, List<String> permissions) {}

    private final MeQueries queries;
    private final CurrentActor currentActor;

    MeController(MeQueries queries, CurrentActor currentActor) {
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Get the signed-in user",
            description =
                    "Returns the caller's own user record (id, tenant, scope, email, display name, status) and the "
                    + "permission codes the caller holds across the suite. The console reads it to decide what to show. Any "
                    + "authenticated user may call it; a token whose user no longer exists answers `404`.")
    @GetMapping("/api/v1/me")
    @Authenticated
    MeResponse me() {
        UUID caller = currentActor.get().id();
        return queries.findById(caller)
                .map(me -> new MeResponse(
                        me.user().id(), me.user().tenantId(), me.user().scope(), me.user().email(), me.user().displayName(),
                        me.user().status(), me.permissions()))
                .orElseThrow(() -> new NotFoundException("no user for id " + caller));
    }
}
