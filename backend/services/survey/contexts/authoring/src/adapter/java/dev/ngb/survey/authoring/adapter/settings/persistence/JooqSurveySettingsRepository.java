package dev.ngb.survey.authoring.adapter.settings.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_SETTINGS;

import dev.ngb.survey.authoring.application.settings.port.SurveySettingsRepository;
import dev.ngb.survey.authoring.application.settings.port.TenantSurveySettings;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** The tenant settings row: one per tenant, written on the first change and read on every publish. */
public class JooqSurveySettingsRepository implements SurveySettingsRepository {

    private final DSLContext dsl;

    public JooqSurveySettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TenantSurveySettings> find(UUID tenantId) {
        return dsl.selectFrom(SURVEY_SETTINGS)
                .where(SURVEY_SETTINGS.TENANT_ID.eq(tenantId))
                .fetchOptional(this::toSettings);
    }

    @Override
    public void save(TenantSurveySettings settings) {
        // An insert-on-conflict keeps the write one statement, so two settings screens saving at once cannot lose one
        // another's row by racing a select-then-insert.
        dsl.insertInto(SURVEY_SETTINGS)
                .set(SURVEY_SETTINGS.TENANT_ID, settings.tenantId())
                .set(SURVEY_SETTINGS.DEFAULT_LOCALE, settings.defaultLocale())
                .set(SURVEY_SETTINGS.ABANDON_AFTER, interval(settings.abandonAfter()))
                .set(SURVEY_SETTINGS.UPLOAD_MAX_BYTES, settings.uploadMaxBytes())
                .set(SURVEY_SETTINGS.PUBLIC_RATE_PER_MINUTE, settings.publicRatePerMinute())
                .set(SURVEY_SETTINGS.TIMEZONE, settings.timezone())
                .set(SURVEY_SETTINGS.VERSION, settings.version())
                .onConflict(SURVEY_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(SURVEY_SETTINGS.DEFAULT_LOCALE, settings.defaultLocale())
                .set(SURVEY_SETTINGS.ABANDON_AFTER, interval(settings.abandonAfter()))
                .set(SURVEY_SETTINGS.UPLOAD_MAX_BYTES, settings.uploadMaxBytes())
                .set(SURVEY_SETTINGS.PUBLIC_RATE_PER_MINUTE, settings.publicRatePerMinute())
                .set(SURVEY_SETTINGS.TIMEZONE, settings.timezone())
                .set(SURVEY_SETTINGS.VERSION, settings.version())
                .execute();
    }

    /** The interval a duration becomes, in the type the column holds. */
    private static org.jooq.types.YearToSecond interval(Duration duration) {
        return duration == null ? null : org.jooq.types.YearToSecond.valueOf(duration);
    }

    private TenantSurveySettings toSettings(Record row) {
        return new TenantSurveySettings(
                row.get(SURVEY_SETTINGS.TENANT_ID),
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
