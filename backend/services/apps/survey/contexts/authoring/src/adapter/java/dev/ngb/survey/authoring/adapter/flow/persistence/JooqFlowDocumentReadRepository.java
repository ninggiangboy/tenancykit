package dev.ngb.survey.authoring.adapter.flow.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_DRAFTS;
import static dev.ngb.survey_jooq.Tables.SURVEY_VERSIONS;

import dev.ngb.survey.authoring.application.flow.query.FlowDocumentReadRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the two documents a preview or a simulation runs over: the current draft and one published version.
 *
 * <p>Both queries are selects; the tenant comes from the bound scope, so row-level security is what keeps another
 * tenant's document out of reach.
 */
public class JooqFlowDocumentReadRepository implements FlowDocumentReadRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqFlowDocumentReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Optional<Map<String, Object>> draftDocument(UUID surveyId) {
        return dsl.select(SURVEY_DRAFTS.DOCUMENT)
                .from(SURVEY_DRAFTS)
                .where(SURVEY_DRAFTS.SURVEY_ID.eq(surveyId))
                .fetchOptional(row -> document(row.value1().data()));
    }

    @Override
    public Optional<Map<String, Object>> versionDocument(UUID surveyId, int versionNo) {
        return dsl.select(SURVEY_VERSIONS.DOCUMENT)
                .from(SURVEY_VERSIONS)
                .where(SURVEY_VERSIONS.SURVEY_ID.eq(surveyId))
                .and(SURVEY_VERSIONS.VERSION_NO.eq(versionNo))
                .fetchOptional(row -> document(row.value1().data()));
    }

    private Map<String, Object> document(String stored) {
        Map<String, Object> document = json.readValue(stored, DOCUMENT);
        return document == null ? Map.of() : document;
    }
}
