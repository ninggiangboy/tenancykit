package dev.ngb.survey.authoring.adapter.settings.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_SETTINGS;

import dev.ngb.survey.authoring.application.settings.query.SurveySettingsReadRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** The read side of the tenant settings row, next to its write repository. */
public class JooqSurveySettingsReadRepository implements SurveySettingsReadRepository {

    private final DSLContext dsl;

    public JooqSurveySettingsReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<SettingsView> find(UUID tenantId) {
        return dsl.selectFrom(SURVEY_SETTINGS)
                .where(SURVEY_SETTINGS.TENANT_ID.eq(tenantId))
                .fetchOptional(this::toView);
    }

    private SettingsView toView(Record row) {
        return new SettingsView(
                row.get(SURVEY_SETTINGS.DEFAULT_LOCALE),
                row.get(SURVEY_SETTINGS.ABANDON_AFTER) == null
                        ? null
                        : Duration.ofSeconds(row.get(SURVEY_SETTINGS.ABANDON_AFTER).toDuration().getSeconds()),
                row.get(SURVEY_SETTINGS.UPLOAD_MAX_BYTES),
                row.get(SURVEY_SETTINGS.PUBLIC_RATE_PER_MINUTE),
                row.get(SURVEY_SETTINGS.TIMEZONE),
                row.get(SURVEY_SETTINGS.VERSION));
    }
}
