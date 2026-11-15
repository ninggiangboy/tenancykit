package dev.ngb.email.sending.application.settings.port;

import dev.ngb.email.sending.application.email.port.EmailSettingsView;

/** The tenant's settings row; a missing row is the configured defaults, not an error. */
public interface EmailSettingsRepository {

    EmailSettingsView current();

    EmailSettingsView save(EmailSettingsView settings);
}
