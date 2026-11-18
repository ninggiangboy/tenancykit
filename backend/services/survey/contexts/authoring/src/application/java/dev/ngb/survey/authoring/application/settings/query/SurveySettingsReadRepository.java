package dev.ngb.survey.authoring.application.settings.query;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** The read side of the tenant settings row, separate from the write side so a read never drives a write. */
public interface SurveySettingsReadRepository {

    /** The tenant's row, or empty when it never changed a default. */
    Optional<SettingsView> find(UUID tenantId);

    /** The settings a settings screen shows: the configured values and the version a change compares. */
    record SettingsView(
            String defaultLocale,
            Duration abandonAfter,
            Long uploadMaxBytes,
            Integer publicRatePerMinute,
            String timezone,
            long version) {}
}
