package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.links.application.settings.command.UpdateSettingsUseCase;
import dev.ngb.attribution.links.application.settings.query.SettingsReadRepository;
import dev.ngb.attribution.links.application.settings.query.SettingsView;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.RequiresPermission;
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
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final UpdateSettingsUseCase update;
    private final SettingsReadRepository settings;
    private final UnitOfWork unitOfWork;
    private final String defaultClickParam;
    private final int defaultWindowDays;

    public SettingsController(
            UpdateSettingsUseCase update,
            SettingsReadRepository settings,
            UnitOfWork unitOfWork,
            String defaultClickParam,
            int defaultWindowDays) {
        this.update = update;
        this.settings = settings;
        this.unitOfWork = unitOfWork;
        this.defaultClickParam = defaultClickParam;
        this.defaultWindowDays = defaultWindowDays;
    }

    @GetMapping
    @RequiresPermission(AttributionPermissions.SETTINGS_MANAGE)
    SettingsView get() {
        return unitOfWork.transactional(() -> settings.load(defaultClickParam, defaultWindowDays));
    }

    @PutMapping
    @RequiresPermission(AttributionPermissions.SETTINGS_MANAGE)
    SettingsView put(@RequestBody SettingsRequest request) {
        update.execute(new UpdateSettingsUseCase.Change(
                request.click_param(), request.allowed_origins(), request.default_window_days()));
        // Reloaded through the read side, so the answer is what was stored and not what the request hoped for.
        return unitOfWork.transactional(() -> settings.load(defaultClickParam, defaultWindowDays));
    }

    /** The wire shape uses snake_case, as the contract does; the view speaks the domain's names. */
    record SettingsRequest(String click_param, List<String> allowed_origins, Integer default_window_days) {}
}
