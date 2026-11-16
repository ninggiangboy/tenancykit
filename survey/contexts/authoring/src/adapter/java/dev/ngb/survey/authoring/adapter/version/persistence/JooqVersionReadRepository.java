package dev.ngb.survey.authoring.adapter.version.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.survey.authoring.application.version.query.VersionListing;
import dev.ngb.survey.authoring.application.version.query.VersionReadRepository;
import dev.ngb.survey.authoring.application.version.query.VersionView;
import dev.ngb.survey_jooq.tables.records.SurveyVersionsRecord;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The read side of a survey's immutable versions, newest first. */
public class JooqVersionReadRepository implements VersionReadRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    /** The version fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(VersionListing.SCHEMA)
            .column("id", SURVEY_VERSIONS.ID)
            .column("survey_id", SURVEY_VERSIONS.SURVEY_ID)
            .column("version_no", SURVEY_VERSIONS.VERSION_NO)
            .column("published_at", SURVEY_VERSIONS.PUBLISHED_AT)
            .column("published_by", SURVEY_VERSIONS.PUBLISHED_BY)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    /** @return the binding of the version listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqVersionReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<VersionView> list(ListQuery query, UUID surveyId) {
        return JooqLists.fetch(
                dsl, SURVEY_VERSIONS, SURVEY_VERSIONS.SURVEY_ID.eq(surveyId), BINDING, query, this::toView);
    }

    @Override
    public Optional<VersionView> find(UUID surveyId, int versionNo) {
        return dsl.selectFrom(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .and(SURVEY_VERSIONS.VERSION_NO.eq(versionNo))
                .fetchOptional(this::toView);
    }

    private VersionView toView(SurveyVersionsRecord row) {
        return new VersionView(
                row.getId(),
                row.getVersionNo(),
                row.getPublishedAt(),
                json.readValue(row.getDocument().data(), DOCUMENT));
    }
}
