package dev.ngb.mailroom.sending.application.settings.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;
import dev.ngb.mailroom.sending.application.settings.port.EmailSettingsRepository;

/** Saves the tenant's sender, reply-to, retention and campaign rate, bounded as the schema states. */
public class SaveEmailSettingsUseCase {

    public record Command(
            String fromKey,
            String fromName,
            String replyTo,
            Integer bodyRetentionDays,
            Integer campaignPublishPerMinute) {}

    private final UnitOfWork unitOfWork;
    private final EmailSettingsRepository settings;

    public SaveEmailSettingsUseCase(UnitOfWork unitOfWork, EmailSettingsRepository settings) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
    }

    public EmailSettingsView execute(Command command) {
        return unitOfWork.transactional(() -> {
            EmailSettingsView current = settings.current();
            String fromKey = command.fromKey() == null || command.fromKey().isBlank()
                    ? current.fromKey()
                    : command.fromKey().trim();
            int retention =
                    command.bodyRetentionDays() == null ? current.bodyRetentionDays() : command.bodyRetentionDays();
            if (retention < 1 || retention > 3650) {
                throw new BadRequestException("body retention is between 1 and 3650 days");
            }
            int rate = command.campaignPublishPerMinute() == null
                    ? current.campaignPublishPerMinute()
                    : command.campaignPublishPerMinute();
            if (rate < 1 || rate > 100_000) {
                throw new BadRequestException("campaign publish per minute is between 1 and 100000");
            }
            EmailSettingsView updated = new EmailSettingsView(
                    fromKey, command.fromName(), command.replyTo(), retention, rate, current.version());
            return settings.save(updated);
        });
    }
}
