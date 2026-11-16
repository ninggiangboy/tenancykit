package dev.ngb.survey.authoring.adapter.survey.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEYS;
import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.survey.authoring.application.survey.query.SurveyListing;
import dev.ngb.survey.authoring.application.survey.query.SurveyReadRepository;
import dev.ngb.survey.authoring.application.survey.query.SurveyView;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import dev.ngb.survey_jooq.tables.records.SurveysRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** The survey fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(SurveyListing.SCHEMA)
            .column("id", SURVEYS.ID)
            .column("name", SURVEYS.NAME)
            .column("status", SURVEYS.STATUS)
            .column("current_version_id", SURVEYS.CURRENT_VERSION_ID)
            .column("created_at", SURVEYS.CREATED_AT)
            .column("updated_at", SURVEYS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the survey listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqSurveyReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<SurveyView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, SURVEYS, noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Optional<SurveyView> find(UUID surveyId) {
        return base().where(SURVEYS.ID.eq(surveyId)).fetchOptional(JooqSurveyReadRepository::toView);
    }

    /**
     * Numbers the current version of a page of surveys in one query: the join in {@link #base()} cannot be used by the
     * listing, because the contract narrows and orders the survey table itself.
     */
    private List<SurveyView> toViews(List<SurveysRecord> rows) {
        List<UUID> versionIds = rows.stream()
                .map(SurveysRecord::getCurrentVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Integer> numbers = versionIds.isEmpty()
                ? Map.of()
                : dsl.select(SURVEY_VERSIONS.ID, SURVEY_VERSIONS.VERSION_NO)
                        .from(SURVEY_VERSIONS)
                        .where(SURVEY_VERSIONS.ID.in(versionIds))
                        .fetchMap(SURVEY_VERSIONS.ID, SURVEY_VERSIONS.VERSION_NO);
        return rows.stream()
                .map(row -> new SurveyView(
                        row.getId(),
                        row.getName(),
                        SurveyStatus.valueOf(row.getStatus()),
                        row.getCurrentVersionId(),
                        numbers.get(row.getCurrentVersionId()),
                        row.getVersion()))
                .toList();
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
