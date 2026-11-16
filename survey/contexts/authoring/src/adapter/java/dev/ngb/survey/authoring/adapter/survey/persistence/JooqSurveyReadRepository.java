package dev.ngb.survey.authoring.adapter.survey.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEYS;
import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;

import dev.ngb.survey.authoring.application.survey.query.SurveyReadRepository;
import dev.ngb.survey.authoring.application.survey.query.SurveyView;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record6;
import org.jooq.SelectJoinStep;

/**
 * The survey listing and detail, with the current version's number resolved in the same query so a client never sees a
 * version id it cannot number.
 */
public class JooqSurveyReadRepository implements SurveyReadRepository {

    private final DSLContext dsl;

    public JooqSurveyReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<SurveyView> list(int limit) {
        return base().orderBy(SURVEYS.CREATED_AT.desc(), SURVEYS.ID)
                .limit(limit)
                .fetch(JooqSurveyReadRepository::toView);
    }

    @Override
    public Optional<SurveyView> find(UUID surveyId) {
        return base().where(SURVEYS.ID.eq(surveyId)).fetchOptional(JooqSurveyReadRepository::toView);
    }

    /** The join is left: a survey never published points at no version and must still be listed. */
    private SelectJoinStep<Record6<UUID, String, String, UUID, Integer, Long>> base() {
        return dsl.select(
                        SURVEYS.ID,
                        SURVEYS.NAME,
                        SURVEYS.STATUS,
                        SURVEYS.CURRENT_VERSION_ID,
                        SURVEY_VERSIONS.VERSION_NO,
                        SURVEYS.VERSION)
                .from(SURVEYS)
                .leftJoin(SURVEY_VERSIONS)
                .on(SURVEY_VERSIONS.ID.eq(SURVEYS.CURRENT_VERSION_ID)
                        .and(SURVEY_VERSIONS.TENANT_ID.eq(SURVEYS.TENANT_ID)));
    }

    private static SurveyView toView(Record6<UUID, String, String, UUID, Integer, Long> row) {
        return new SurveyView(
                row.value1(),
                row.value2(),
                SurveyStatus.valueOf(row.value3()),
                row.value4(),
                row.value5(),
                row.value6());
    }
}
