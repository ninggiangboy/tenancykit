package dev.ngb.survey.authoring.adapter.version.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;

import dev.ngb.survey.authoring.application.version.query.VersionReadRepository;
import dev.ngb.survey.authoring.application.version.query.VersionView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The read side of a survey's immutable versions, newest first. */
public class JooqVersionReadRepository implements VersionReadRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqVersionReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<VersionView> list(UUID surveyId) {
        return dsl.selectFrom(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .orderBy(SURVEY_VERSIONS.VERSION_NO.desc())
                .fetch(row -> toView(row));
    }

    @Override
    public Optional<VersionView> find(UUID surveyId, int versionNo) {
        return dsl.selectFrom(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .and(SURVEY_VERSIONS.VERSION_NO.eq(versionNo))
                .fetchOptional(row -> toView(row));
    }

    private VersionView toView(Record row) {
        return new VersionView(
                row.get(SURVEY_VERSIONS.ID),
                row.get(SURVEY_VERSIONS.VERSION_NO),
                row.get(SURVEY_VERSIONS.PUBLISHED_AT),
                json.readValue(row.get(SURVEY_VERSIONS.DOCUMENT).data(), DOCUMENT));
    }
}
