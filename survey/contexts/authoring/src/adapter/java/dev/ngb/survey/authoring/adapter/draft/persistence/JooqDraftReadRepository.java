package dev.ngb.survey.authoring.adapter.draft.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_DRAFTS;

import dev.ngb.survey.authoring.application.draft.query.DraftReadRepository;
import dev.ngb.survey.authoring.application.draft.query.DraftView;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;

/** The read side of a survey's draft, next to its write repository and sharing the document codec. */
public class JooqDraftReadRepository implements DraftReadRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqDraftReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Optional<DraftView> find(UUID surveyId) {
        return dsl.select(SURVEY_DRAFTS.DOCUMENT, SURVEY_DRAFTS.ETAG, SURVEY_DRAFTS.UPDATED_AT)
                .from(SURVEY_DRAFTS)
                .where(SURVEY_DRAFTS.SURVEY_ID.eq(surveyId))
                .fetchOptional(row -> new DraftView(
                        json.readValue(row.value1().data(), JooqDraftRepository.DOCUMENT),
                        row.value2(),
                        row.value3()));
    }
}
