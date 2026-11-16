package dev.ngb.survey.authoring.adapter.version.persistence;

import static dev.ngb.survey_jooq.Tables.QUESTION_LINEAGE;
import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.authoring.application.version.port.VersionRepository;
import dev.ngb.survey.authoring.domain.version.QuestionLineage;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The version store. It inserts and reads; the table's grant is what forbids an update or a delete. */
public class JooqVersionRepository implements VersionRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqVersionRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(SurveyVersion version, List<QuestionLineage> lineage, String publishedBy) {
        dsl.insertInto(SURVEY_VERSIONS)
                .set(SURVEY_VERSIONS.ID, version.id())
                .set(SURVEY_VERSIONS.SURVEY_ID, version.surveyId())
                .set(SURVEY_VERSIONS.TENANT_ID, version.tenantId().value())
                .set(SURVEY_VERSIONS.VERSION_NO, version.versionNo())
                .set(SURVEY_VERSIONS.DOCUMENT, JSONB.valueOf(json.writeValueAsString(version.document())))
                .set(SURVEY_VERSIONS.PUBLISHED_AT, version.publishedAt())
                .set(SURVEY_VERSIONS.PUBLISHED_BY, publishedBy)
                .execute();
        for (QuestionLineage row : lineage) {
            dsl.insertInto(QUESTION_LINEAGE)
                    .set(QUESTION_LINEAGE.SURVEY_ID, row.surveyId())
                    .set(QUESTION_LINEAGE.QUESTION_ID, row.questionId())
                    .set(QUESTION_LINEAGE.TENANT_ID, row.tenantId().value())
                    .set(QUESTION_LINEAGE.REPLACES, row.replaces())
                    .set(QUESTION_LINEAGE.FIRST_VERSION_NO, row.firstVersionNo())
                    .execute();
        }
    }

    @Override
    public Optional<SurveyVersion> latest(UUID surveyId) {
        return dsl.selectFrom(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .orderBy(SURVEY_VERSIONS.VERSION_NO.desc())
                .limit(1)
                .fetchOptional(row -> toVersion(row));
    }

    @Override
    public Optional<SurveyVersion> byNumber(UUID surveyId, int versionNo) {
        return dsl.selectFrom(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .and(SURVEY_VERSIONS.VERSION_NO.eq(versionNo))
                .fetchOptional(row -> toVersion(row));
    }

    private SurveyVersion toVersion(Record row) {
        return new SurveyVersion(
                row.get(SURVEY_VERSIONS.ID),
                new TenantId(row.get(SURVEY_VERSIONS.TENANT_ID)),
                row.get(SURVEY_VERSIONS.SURVEY_ID),
                row.get(SURVEY_VERSIONS.VERSION_NO),
                json.readValue(row.get(SURVEY_VERSIONS.DOCUMENT).data(), DOCUMENT),
                row.get(SURVEY_VERSIONS.PUBLISHED_AT));
    }
}
