package dev.ngb.survey.authoring.adapter.survey.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEYS;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** The write side of surveys; every statement runs inside the caller's tenant-scoped transaction. */
public class JooqSurveyRepository implements SurveyRepository {

    private final DSLContext dsl;

    public JooqSurveyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Survey survey) {
        dsl.insertInto(SURVEYS)
                .set(SURVEYS.ID, survey.id())
                .set(SURVEYS.TENANT_ID, survey.tenantId().value())
                .set(SURVEYS.NAME, survey.name())
                .set(SURVEYS.STATUS, survey.status().name())
                .set(SURVEYS.CURRENT_VERSION_ID, survey.currentVersionId())
                .set(SURVEYS.VERSION, survey.version())
                .set(SURVEYS.CREATED_AT, survey.createdAt())
                .set(SURVEYS.UPDATED_AT, survey.updatedAt())
                .execute();
    }

    @Override
    public Optional<Survey> findById(UUID surveyId) {
        return dsl.selectFrom(SURVEYS)
                .where(SURVEYS.ID.eq(surveyId))
                .fetchOptional(JooqSurveyRepository::toSurvey);
    }

    @Override
    public boolean update(Survey survey, long expectedVersion) {
        return dsl.update(SURVEYS)
                        .set(SURVEYS.NAME, survey.name())
                        .set(SURVEYS.STATUS, survey.status().name())
                        .set(SURVEYS.CURRENT_VERSION_ID, survey.currentVersionId())
                        .set(SURVEYS.VERSION, survey.version())
                        .set(SURVEYS.UPDATED_AT, survey.updatedAt())
                        .where(SURVEYS.ID.eq(survey.id()))
                        .and(SURVEYS.TENANT_ID.eq(survey.tenantId().value()))
                        .and(SURVEYS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    private static Survey toSurvey(Record row) {
        return new Survey(
                row.get(SURVEYS.ID),
                new TenantId(row.get(SURVEYS.TENANT_ID)),
                row.get(SURVEYS.NAME),
                SurveyStatus.valueOf(row.get(SURVEYS.STATUS)),
                row.get(SURVEYS.CURRENT_VERSION_ID),
                row.get(SURVEYS.VERSION),
                row.get(SURVEYS.CREATED_AT),
                row.get(SURVEYS.UPDATED_AT));
    }
}
