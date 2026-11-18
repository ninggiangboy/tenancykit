package dev.ngb.survey.authoring.adapter.draft.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_DRAFTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.authoring.application.draft.port.DraftRepository;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The draft store: the JSONB document is kept exactly as the server stored it, ids included. */
public class JooqDraftRepository implements DraftRepository {

    static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqDraftRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(SurveyDraft draft) {
        dsl.insertInto(SURVEY_DRAFTS)
                .set(SURVEY_DRAFTS.SURVEY_ID, draft.surveyId())
                .set(SURVEY_DRAFTS.TENANT_ID, draft.tenantId().value())
                .set(SURVEY_DRAFTS.DOCUMENT, toJson(draft.document()))
                .set(SURVEY_DRAFTS.ETAG, draft.etag())
                .set(SURVEY_DRAFTS.UPDATED_AT, draft.updatedAt())
                .execute();
    }

    @Override
    public Optional<SurveyDraft> findBySurveyId(UUID surveyId) {
        return dsl.selectFrom(SURVEY_DRAFTS)
                .where(SURVEY_DRAFTS.SURVEY_ID.eq(surveyId))
                .fetchOptional(row -> new SurveyDraft(
                        row.getSurveyId(),
                        new TenantId(row.getTenantId()),
                        fromJson(row.getDocument()),
                        row.getEtag(),
                        row.getUpdatedAt()));
    }

    @Override
    public boolean save(SurveyDraft draft, long expectedEtag, String updatedBy) {
        return dsl.update(SURVEY_DRAFTS)
                        .set(SURVEY_DRAFTS.DOCUMENT, toJson(draft.document()))
                        .set(SURVEY_DRAFTS.ETAG, draft.etag())
                        .set(SURVEY_DRAFTS.UPDATED_AT, draft.updatedAt())
                        .set(SURVEY_DRAFTS.UPDATED_BY, updatedBy)
                        .where(SURVEY_DRAFTS.SURVEY_ID.eq(draft.surveyId()))
                        .and(SURVEY_DRAFTS.TENANT_ID.eq(draft.tenantId().value()))
                        .and(SURVEY_DRAFTS.ETAG.eq(expectedEtag))
                        .execute()
                == 1;
    }

    private JSONB toJson(Map<String, Object> document) {
        return JSONB.valueOf(json.writeValueAsString(document));
    }

    private Map<String, Object> fromJson(JSONB document) {
        return json.readValue(document.data(), DOCUMENT);
    }
}
