package dev.ngb.platform.tenancy.adapter.uiremote.web;

import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteQueries;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feed a shell reads on load. It is filtered on the server: only remotes this caller may mount, each with the one
 * version an editor activated.
 */
@Tag(
        name = "Console feed",
        description =
                "The feed a console shell reads on load: the UI remotes the caller may mount.")
@RestController
@RequestMapping("/api/v1/console")
class ConsoleRemoteController {

    record ConsoleRemoteResponse(
            String name, String title, String appCode, int navOrder, String version, String entryPath, String integrity,
            int sdkMajor) {

        static ConsoleRemoteResponse of(ConsoleRemoteView view) {
            return new ConsoleRemoteResponse(
                    view.name(), view.title(), view.appCode(), view.navOrder(), view.version(), view.entryPath(),
                    view.integrity(), view.sdkMajor());
        }
    }

    private final ConsoleRemoteQueries queries;
    private final CurrentActor currentActor;

    ConsoleRemoteController(ConsoleRemoteQueries queries, CurrentActor currentActor) {
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Get the console's UI remotes",
            description =
                    "The feed a console shell reads on load: only the remotes the caller may mount, each with the one "
                    + "version an operator activated, filtered on the server. Any authenticated user may call it.")
    @GetMapping("/remotes")
    @Authenticated
    List<ConsoleRemoteResponse> feed() {
        return queries.feed(currentActor.get()).stream().map(ConsoleRemoteResponse::of).toList();
    }
}
