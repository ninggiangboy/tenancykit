package dev.ngb.email.sending.adapter.settings.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.settings.command.SaveEmailSettingsUseCase;
import dev.ngb.email.sending.application.settings.service.SettingsReader;
import dev.ngb.email.shared.EmailPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's default sender, reply-to, body retention and campaign publish rate. */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    private final SettingsReader settings;
    private final SaveEmailSettingsUseCase save;

    SettingsController(SettingsReader settings, SaveEmailSettingsUseCase save) {
        this.settings = settings;
        this.save = save;
    }

    @GetMapping
    @RequiresPermission(EmailPermissions.EMAIL_READ)
    SettingsResponse current() {
        return SettingsResponse.of(settings.current());
    }

    @PutMapping
    @RequiresPermission(EmailPermissions.SETTINGS_MANAGE)
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
