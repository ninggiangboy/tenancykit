package dev.ngb.mailroom.sending.adapter.settings.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;
import dev.ngb.mailroom.sending.application.settings.command.SaveEmailSettingsUseCase;
import dev.ngb.mailroom.sending.application.settings.service.SettingsReader;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's default sender, reply-to, body retention and campaign publish rate. */
@Tag(
        name = "Mail settings",
        description =
                "The tenant's default sender, reply-to, body retention and campaign publish rate.")
@RestController
@RequestMapping("/api/v1/settings")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class SettingsController {

    private final SettingsReader settings;
    private final SaveEmailSettingsUseCase save;

    SettingsController(SettingsReader settings, SaveEmailSettingsUseCase save) {
        this.settings = settings;
        this.save = save;
    }

    @Operation(
            summary = "Get the tenant's mail settings",
            description =
                    "Returns the tenant's default sender, reply-to, body retention in days and campaign publish rate per "
                    + "minute, with the settings' version.")
    @GetMapping
    @RequiresPermission(MailroomPermissions.EMAIL_READ)
    SettingsResponse current() {
        return SettingsResponse.of(settings.current());
    }

    @Callers(Caller.TENANT_USER)
    @Operation(
            summary = "Replace the tenant's mail settings",
            description =
                    "Stores the default sender (`fromKey`, `fromName`), `replyTo`, `bodyRetentionDays` and "
                    + "`campaignPublishPerMinute`, each bounded as the schema states. The publish rate is the tenant's "
                    + "total across all its campaigns.")
    @PutMapping
    @RequiresPermission(MailroomPermissions.SETTINGS_MANAGE)
    SettingsResponse save(@RequestBody SettingsRequest request) {
        return SettingsResponse.of(save.execute(new SaveEmailSettingsUseCase.Command(
                request.fromKey(),
                request.fromName(),
                request.replyTo(),
                request.bodyRetentionDays(),
                request.campaignPublishPerMinute())));
    }

    record SettingsRequest(
            String fromKey, String fromName, String replyTo,
            Integer bodyRetentionDays, Integer campaignPublishPerMinute) {}

    record SettingsResponse(
            String fromKey, String fromName, String replyTo,
            int bodyRetentionDays, int campaignPublishPerMinute, long version) {

        static SettingsResponse of(EmailSettingsView view) {
            return new SettingsResponse(
                    view.fromKey(), view.fromName(), view.replyTo(), view.bodyRetentionDays(),
                    view.campaignPublishPerMinute(), view.version());
        }
    }
}
