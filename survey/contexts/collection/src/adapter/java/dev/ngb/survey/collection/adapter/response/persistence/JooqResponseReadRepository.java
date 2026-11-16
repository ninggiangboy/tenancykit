package dev.ngb.survey.collection.adapter.response.persistence;

import static dev.ngb.survey_jooq.Tables.RESPONSES;

import dev.ngb.survey.collection.application.response.query.ResponseReadRepository;
import dev.ngb.survey.collection.application.response.query.ResponseView;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The read side of responses, next to its write repository and sharing the JSON codec. */
public class JooqResponseReadRepository implements ResponseReadRepository {

    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqResponseReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Optional<ResponseView> findByVisitId(UUID visitId) {
        return dsl.selectFrom(RESPONSES)
                .where(RESPONSES.VISIT_ID.eq(visitId))
                .fetchOptional(this::toView);
    }

    @Override
    public Optional<ResponseView> findById(UUID responseId) {
        return dsl.selectFrom(RESPONSES)
                .where(RESPONSES.ID.eq(responseId))
                .fetchOptional(this::toView);
    }

    private ResponseView toView(Record row) {
        return new ResponseView(
                row.get(RESPONSES.ID),
                row.get(RESPONSES.SURVEY_ID),
                row.get(RESPONSES.VISIT_ID),
                row.get(RESPONSES.VERSION_ID),
                row.get(RESPONSES.STATUS),
                row.get(RESPONSES.LOCALE),
                read(row.get(RESPONSES.ANSWERS)),
                read(row.get(RESPONSES.VARS)),
                row.get(RESPONSES.SCORE),
                row.get(RESPONSES.RESPONDENT_IDENTITY),
                row.get(RESPONSES.EXTERNAL_REF),
                row.get(RESPONSES.SOURCE),
                row.get(RESPONSES.STARTED_AT),
                row.get(RESPONSES.SUBMITTED_AT));
    }

    private Map<String, Object> read(org.jooq.JSONB stored) {
        Map<String, Object> members = stored == null ? null : json.readValue(stored.data(), JSON);
        return members == null ? Map.of() : members;
    }
}
