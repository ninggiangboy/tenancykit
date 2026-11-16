package dev.ngb.survey.authoring.application.settings.port;

import dev.ngb.survey.authoring.application.settings.port.TenantSurveySettings;
import java.util.Optional;
import java.util.UUID;

/** The tenant's survey defaults: one row per tenant, written on the first change and read on every publish. */
public interface SurveySettingsRepository {

    /** The tenant's row, or empty when it never changed a default. */
    Optional<TenantSurveySettings> find(UUID tenantId);

    /** Writes the row, inserting it when the tenant had none. */
    void save(TenantSurveySettings settings);
}
