package dev.ngb.platform.access.adapter.auth.web;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.platform.access.api.user.MeQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The console reads the signed-in user and what they may do across the suite from here. */
@RestController
class MeController {

    record MeResponse(
            UUID id, UUID tenantId, String scope, String email, String displayName, String status, List<String> permissions) {}

    private final MeQueries queries;
    private final CurrentActor currentActor;

    MeController(MeQueries queries, CurrentActor currentActor) {
        this.queries = queries;
        this.currentActor = currentActor;
    }

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
