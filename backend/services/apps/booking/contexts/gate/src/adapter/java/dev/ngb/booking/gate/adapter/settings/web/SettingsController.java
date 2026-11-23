package dev.ngb.booking.gate.adapter.settings.web;

import dev.ngb.booking.gate.application.settings.service.GateSettings;
import dev.ngb.booking.gate.application.settings.service.GateSettingsService;
import dev.ngb.booking.gate.application.settings.query.GateTimingView;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's timing settings and the public gate's origin/token policy. */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class SettingsController {

    public record GateSettingsRequest(List<String> allowedOrigins, Integer maxTokenTtlSeconds) {}

    public record GateSettingsResponse(List<String> allowedOrigins, int maxTokenTtlSeconds) {}

    private final GateTimingView timings;
    private final GateSettingsService gate;

    public SettingsController(GateTimingView timings, GateSettingsService gate) {
        this.timings = timings;
        this.gate = gate;
    }

    @GetMapping("/settings")
    @RequiresPermission(BookingPermissions.SETTINGS_READ)
    public GateTimingView settings() {
        return timings;
    }

    @Callers(Caller.TENANT_USER)
    @PutMapping("/settings/gate")
    @RequiresPermission(BookingPermissions.SETTINGS_MANAGE)
    public GateSettingsResponse putGate(@RequestBody GateSettingsRequest request) {
        GateSettings saved = gate.update(request.allowedOrigins(), request.maxTokenTtlSeconds());
        return new GateSettingsResponse(saved.allowedOrigins(), saved.maxTokenTtlSeconds());
    }
}
