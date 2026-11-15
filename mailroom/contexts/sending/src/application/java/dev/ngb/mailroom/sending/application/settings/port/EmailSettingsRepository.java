package dev.ngb.mailroom.sending.application.settings.port;

import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;

/** The tenant's settings row; a missing row is the configured defaults, not an error. */
public interface EmailSettingsRepository {

    EmailSettingsView current();

    EmailSettingsView save(EmailSettingsView settings);
}
