package dev.ngb.survey.collection.adapter.response.persistence;

import static dev.ngb.survey_jooq.Tables.ANSWERS;
import static dev.ngb.survey_jooq.Tables.RESPONSES;
import static dev.ngb.survey_jooq.Tables.SURVEY_COUNTERS;

import dev.ngb.survey.collection.application.response.port.ResponseRepository;
import dev.ngb.survey.collection.application.response.port.TypedAnswer;
import dev.ngb.survey.collection.domain.response.Response;
import dev.ngb.survey.collection.domain.response.ResponseStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The response store. Completion is one conditional update plus the typed rows, so the cap, single use and idempotency
 * are the database's decision rather than a check this class could lose a race to.
 */
public class JooqResponseRepository implements ResponseRepository {

    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqResponseRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Response response) {
        dsl.insertInto(RESPONSES)
                .set(RESPONSES.ID, response.id())
                .set(RESPONSES.TENANT_ID, response.tenantId())
                .set(RESPONSES.SURVEY_ID, response.surveyId())
                .set(RESPONSES.VERSION_ID, response.versionId())
                .set(RESPONSES.LINK_ID, response.linkId())
                .set(RESPONSES.VISIT_ID, response.visitId())
                .set(RESPONSES.STATUS, response.status().name())
                .set(RESPONSES.LOCALE, response.locale())
                .set(RESPONSES.ANSWERS, JSONB.valueOf(json.writeValueAsString(response.answers())))
                .set(RESPONSES.VARS, JSONB.valueOf(json.writeValueAsString(response.vars())))
                .set(RESPONSES.SCORE, response.score())
                .set(RESPONSES.RESPONDENT_IDENTITY, response.respondentIdentity())
                .set(RESPONSES.EXTERNAL_REF, response.externalRef())
                .set(RESPONSES.SOURCE, response.source())
                .set(RESPONSES.IDEMPOTENCY_KEY, response.idempotencyKey())
                .set(RESPONSES.PERSONAL_LINK_ID, response.personalLinkId())
                .set(RESPONSES.STARTED_AT, response.startedAt())
                .set(RESPONSES.SUBMITTED_AT, response.submittedAt())
                .execute();
    }

    @Override
    public Optional<Response> findByVisitId(UUID visitId) {
        return dsl.selectFrom(RESPONSES)
                .where(RESPONSES.VISIT_ID.eq(visitId))
                .fetchOptional(this::toResponse);
    }

    @Override
    public boolean saveDraft(Response response) {
        return dsl.update(RESPONSES)
                .set(RESPONSES.ANSWERS, JSONB.valueOf(json.writeValueAsString(response.answers())))
                .set(RESPONSES.LOCALE, response.locale())
                .where(RESPONSES.ID.eq(response.id()))
                .and(RESPONSES.STATUS.eq(ResponseStatus.IN_PROGRESS.name()))
                .execute()
                > 0;
    }

    @Override
    public boolean countCompletion(UUID surveyId, UUID tenantId, Long cap) {
        // The cap is the condition of the update: a row lock serialises the increment, so a burst completes at most the
        // cap. A survey with no counter row yet is created with the increment in one statement.
        int updated = dsl.update(SURVEY_COUNTERS)
                .set(SURVEY_COUNTERS.COMPLETED, SURVEY_COUNTERS.COMPLETED.plus(1))
                .where(SURVEY_COUNTERS.SURVEY_ID.eq(surveyId))
                .and(SURVEY_COUNTERS.CAP.isNull().or(SURVEY_COUNTERS.COMPLETED.lt(SURVEY_COUNTERS.CAP)))
                .execute();
        if (updated > 0) {
            return true;
        }
        return createCounterAndCount(surveyId, tenantId, cap);
    }

    /**
     * Creates the counter a survey never had and increments it in the same statement, so the first submit counts even
     * when it lost the race to create the row.
     */
    private boolean createCounterAndCount(UUID surveyId, UUID tenantId, Long cap) {
        int inserted = dsl.insertInto(SURVEY_COUNTERS)
                .set(SURVEY_COUNTERS.SURVEY_ID, surveyId)
                .set(SURVEY_COUNTERS.TENANT_ID, tenantId)
                .set(SURVEY_COUNTERS.COMPLETED, 1L)
                .set(SURVEY_COUNTERS.CAP, cap)
                .onConflictDoNothing()
                .execute();
        if (inserted > 0) {
            return true;
        }
        // Another submit created it first; the conditional update is the one that decides from here.
        return dsl.update(SURVEY_COUNTERS)
                        .set(SURVEY_COUNTERS.COMPLETED, SURVEY_COUNTERS.COMPLETED.plus(1))
                        .where(SURVEY_COUNTERS.SURVEY_ID.eq(surveyId))
                        .and(SURVEY_COUNTERS.CAP.isNull()
                                .or(SURVEY_COUNTERS.COMPLETED.lt(SURVEY_COUNTERS.CAP)))
                        .execute()
                > 0;
    }

    @Override
    public boolean complete(Response response, List<TypedAnswer> answers) {
        // The partial unique index on a completed personal link and the status condition are what make this the one
        // place a response becomes final, so a second submit loses here rather than overwriting the first.
        int updated = dsl.update(RESPONSES)
                .set(RESPONSES.STATUS, ResponseStatus.COMPLETED.name())
                .set(RESPONSES.ANSWERS, JSONB.valueOf(json.writeValueAsString(response.answers())))
                .set(RESPONSES.VARS, JSONB.valueOf(json.writeValueAsString(response.vars())))
                .set(RESPONSES.SCORE, response.score())
                .set(RESPONSES.RESPONDENT_IDENTITY, response.respondentIdentity())
                .set(RESPONSES.IDEMPOTENCY_KEY, response.idempotencyKey())
                .set(RESPONSES.LOCALE, response.locale())
                .set(RESPONSES.SUBMITTED_AT, response.submittedAt())
                .where(RESPONSES.ID.eq(response.id()))
                .and(RESPONSES.STATUS.eq(ResponseStatus.IN_PROGRESS.name()))
                .execute();
        if (updated == 0) {
            return false;
        }
        Query[] rows = answers.stream().map(this::insertAnswer).toArray(Query[]::new);
        if (rows.length > 0) {
            dsl.batch(rows).execute();
        }
        return true;
    }

    private Query insertAnswer(TypedAnswer answer) {
        return dsl.insertInto(ANSWERS)
                .set(ANSWERS.ID, UUID.randomUUID())
                .set(ANSWERS.RESPONSE_ID, answer.responseId())
                .set(ANSWERS.TENANT_ID, answer.tenantId())
                .set(ANSWERS.SURVEY_ID, answer.surveyId())
                .set(ANSWERS.VERSION_ID, answer.versionId())
                .set(ANSWERS.QUESTION_ID, answer.questionId())
                .set(ANSWERS.OPTION_ID, answer.optionId())
                .set(ANSWERS.ROW_ID, answer.rowId())
                .set(ANSWERS.NUM_VALUE, answer.numValue())
                .set(ANSWERS.TEXT_VALUE, answer.textValue())
                .set(ANSWERS.DATE_VALUE, answer.dateValue())
                .set(ANSWERS.SOURCE, answer.source())
                .set(ANSWERS.SUBMITTED_AT, answer.submittedAt());
    }

    @Override
    public Optional<UUID> completedByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return dsl.select(RESPONSES.ID)
                .from(RESPONSES)
                .where(RESPONSES.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .and(RESPONSES.STATUS.eq(ResponseStatus.COMPLETED.name()))
                .fetchOptional(row -> row.value1());
    }

    @Override
    public Optional<UUID> visitIdOf(UUID responseId) {
        return dsl.select(RESPONSES.VISIT_ID)
                .from(RESPONSES)
                .where(RESPONSES.ID.eq(responseId))
                .fetchOptional(row -> row.value1());
    }

    @Override
    public void erase(UUID responseId) {
        // The typed rows cascade from the response, so deleting the row is the whole erasure.
        dsl.deleteFrom(RESPONSES).where(RESPONSES.ID.eq(responseId)).execute();
    }

    private Response toResponse(Record row) {
        return new Response(
                row.get(RESPONSES.ID),
                row.get(RESPONSES.TENANT_ID),
                row.get(RESPONSES.SURVEY_ID),
                row.get(RESPONSES.VERSION_ID),
                row.get(RESPONSES.LINK_ID),
                row.get(RESPONSES.VISIT_ID),
                ResponseStatus.valueOf(row.get(RESPONSES.STATUS)),
                row.get(RESPONSES.LOCALE),
                read(row.get(RESPONSES.ANSWERS)),
                read(row.get(RESPONSES.VARS)),
                row.get(RESPONSES.SCORE),
                row.get(RESPONSES.RESPONDENT_IDENTITY),
                row.get(RESPONSES.EXTERNAL_REF),
                row.get(RESPONSES.SOURCE),
                row.get(RESPONSES.IDEMPOTENCY_KEY),
                row.get(RESPONSES.PERSONAL_LINK_ID),
                row.get(RESPONSES.STARTED_AT),
                row.get(RESPONSES.SUBMITTED_AT));
    }

    private Map<String, Object> read(JSONB stored) {
        Map<String, Object> members = stored == null ? null : json.readValue(stored.data(), JSON);
        return members == null ? Map.of() : members;
    }
}
