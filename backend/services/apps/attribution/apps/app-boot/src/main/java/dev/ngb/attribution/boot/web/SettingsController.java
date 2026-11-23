package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.boot.config.AttributionSettings;
import dev.ngb.attribution.links.application.settings.command.UpdateSettingsUseCase;
import dev.ngb.attribution.links.application.settings.query.SettingsReadRepository;
import dev.ngb.attribution.links.application.settings.query.SettingsView;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's attribution settings: the click parameter, the origins allowed to collect, and the default window.
 *
 * <p>Reading is gated on {@code settings:manage} rather than a separate read code, because the same three values decide
 * how the tenant's links behave and who may post to its collection endpoint; a caller that may see them is one that may
 * change them.
 */
@Tag(
        name = "Attribution settings",
        description =
                "The click parameter, the origins allowed to collect and the default attribution window.")
@RestController
@RequestMapping("/settings")
@Callers(Caller.TENANT_USER)
public class SettingsController {

    private final UpdateSettingsUseCase update;
    private final SettingsReadRepository settings;
    private final AttributionSettings attribution;
    private final UnitOfWork unitOfWork;

    public SettingsController(
            UpdateSettingsUseCase update,
            SettingsReadRepository settings,
            UnitOfWork unitOfWork,
            AttributionSettings attribution) {
        this.update = update;
        this.settings = settings;
        this.unitOfWork = unitOfWork;
        this.attribution = attribution;
    }

    @Operation(
            summary = "Get the tenant's attribution settings",
            description =
                    "Returns the click parameter name, the origins allowed to post to the collection endpoint and the "
                    + "default attribution window in days. Reading needs `attribution:settings:manage`, the same code as "
                    + "changing them, because the three values decide how the tenant's links behave and who may post to its "
                    + "collection endpoint.")
    @GetMapping
    @RequiresPermission(AttributionPermissions.SETTINGS_MANAGE)
    SettingsView get() {
        return load();
    }

    @Operation(
            summary = "Replace the tenant's attribution settings",
            description =
                    "Stores `click_param`, `allowed_origins` and `default_window_days`. Each origin must be an absolute "
                    + "`https` origin (or `http://localhost` for development); a wildcard, a path or a bare host answers "
                    + "`400`, because a collection endpoint that accepted a wildcard would let any page post another "
                    + "visitor's events.")
    @PutMapping
    @RequiresPermission(AttributionPermissions.SETTINGS_MANAGE)
    SettingsView put(@RequestBody SettingsRequest request) {
        update.execute(new UpdateSettingsUseCase.Change(
                request.click_param(), request.allowed_origins(), request.default_window_days()));
        // Reloaded through the read side, so the answer is what was stored and not what the request hoped for.
        return load();
    }

    private SettingsView load() {
        return unitOfWork.transactional(() -> settings.load(
                attribution.defaults().clickParam(), attribution.defaults().defaultWindowDays()));
    }

    /** The wire shape uses snake_case, as the contract does; the view speaks the domain's names. */
    record SettingsRequest(String click_param, List<String> allowed_origins, Integer default_window_days) {}
}
