package dev.ngb.survey.collection.adapter.response.persistence;

import static dev.ngb.survey_jooq.Tables.ANSWERS;
import static dev.ngb.survey_jooq.Tables.RESPONSES;
import static dev.ngb.survey_jooq.Tables.VISITS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.survey.collection.api.response.ResponseListing;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.RespondentListing;
import dev.ngb.survey_jooq.tables.records.VisitsRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read side {@code insight} reports from, built only from selects over {@code collection}'s own tables.
 *
 * <p>Every aggregate reads the typed {@code answers} rows rather than the response's JSONB, which is what makes the
 * indexes pay: an option count is a filtered group-by, a mean is an average over `num_value`, and a trend buckets
 * `submitted_at` in the survey's own zone. A deleted response is gone from all of them because there is nothing to
 * keep in step.
 */
public class JooqResponseReader implements ResponseReader {

    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {};

    /** The response fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding RESPONSE_BINDING = ListBinding.of(ResponseListing.SCHEMA)
            .column("id", RESPONSES.ID)
            .column("survey_id", RESPONSES.SURVEY_ID)
            .column("version_id", RESPONSES.VERSION_ID)
            .column("locale", RESPONSES.LOCALE)
            .column("source", RESPONSES.SOURCE)
            .column("respondent_identity", RESPONSES.RESPONDENT_IDENTITY)
            .column("external_ref", RESPONSES.EXTERNAL_REF)
            .column("score", RESPONSES.SCORE)
            .column("started_at", RESPONSES.STARTED_AT)
            .column("submitted_at", RESPONSES.SUBMITTED_AT)
            .build();

    /** The visit fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding RESPONDENT_BINDING = ListBinding.of(RespondentListing.SCHEMA)
            .column("id", VISITS.ID)
            .column("link_id", VISITS.LINK_ID)
            .column("source", VISITS.SOURCE)
            .column("state", VISITS.STATE)
            .column("country", VISITS.COUNTRY)
            .column("device_class", VISITS.DEVICE_CLASS)
            .column("traffic_class", VISITS.TRAFFIC_CLASS)
            .column("response_id", VISITS.RESPONSE_ID)
            .column("opened_at", VISITS.OPENED_AT)
            .column("last_activity_at", VISITS.LAST_ACTIVITY_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    /** @return the bindings of the response and respondent listings, for the test that checks every sortable field */
    public static Map<String, ListBinding> listBindings() {
        return Map.of("response", RESPONSE_BINDING, "respondent", RESPONDENT_BINDING);
    }

    public JooqResponseReader(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<ResponseRow> list(UUID surveyId, ListQuery query) {
        return JooqLists.fetch(dsl, RESPONSES, completed(surveyId), RESPONSE_BINDING, query, this::toRow);
    }

    @Override
    public Page<RespondentRow> respondents(UUID surveyId, ListQuery query) {
        return JooqLists.fetchPage(
                dsl, VISITS, VISITS.SURVEY_ID.eq(surveyId), RESPONDENT_BINDING, query, this::respondents);
    }

    /** Fills in each arrival's identity with one query for the page, instead of one per row. */
    private List<RespondentRow> respondents(List<VisitsRecord> rows) {
        Set<UUID> responseIds = rows.stream()
                .map(VisitsRecord::getResponseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ResponseIdentity> identities = responseIds.isEmpty()
                ? Map.of()
                : dsl.select(RESPONSES.ID, RESPONSES.RESPONDENT_IDENTITY, RESPONSES.EXTERNAL_REF)
                        .from(RESPONSES)
                        .where(RESPONSES.ID.in(responseIds))
                        .fetch()
                        .stream()
                        .collect(Collectors.toMap(row -> row.value1(), row -> new ResponseIdentity(row.value2(), row.value3())));
        return rows.stream()
                .map(row -> {
                    ResponseIdentity identity = identities.get(row.getResponseId());
                    return new RespondentRow(
                            row.getId(),
                            row.getResponseId(),
                            identity == null ? null : identity.respondentIdentity(),
                            identity == null ? null : identity.externalRef(),
                            row.getSource(),
                            row.getState(),
                            row.getLastActivityAt());
                })
                .toList();
    }

    /** The pair a respondent row takes from the response it produced. */
    private record ResponseIdentity(String respondentIdentity, String externalRef) {}

    @Override
    public List<ResponseRow> responses(UUID surveyId, ResponseFilter filter, int offset, int limit) {
        return dsl.selectFrom(RESPONSES)
                .where(completed(surveyId, filter))
                .orderBy(RESPONSES.SUBMITTED_AT.asc(), RESPONSES.ID.asc())
                .limit(limit)
                .offset(offset)
                .fetch(this::toRow);
    }

    @Override
    public long count(UUID surveyId, ResponseFilter filter) {
        return dsl.fetchCount(RESPONSES, completed(surveyId, filter));
    }

    @Override
    public Optional<ResponseRow> response(UUID responseId) {
        return dsl.selectFrom(RESPONSES)
                .where(RESPONSES.ID.eq(responseId))
                .fetchOptional(this::toRow);
    }

    @Override
    public List<AnswerRow> answers(UUID responseId) {
        return dsl.selectFrom(ANSWERS)
                .where(ANSWERS.RESPONSE_ID.eq(responseId))
                .fetch(row -> new AnswerRow(
                        row.get(ANSWERS.QUESTION_ID),
                        row.get(ANSWERS.OPTION_ID),
                        row.get(ANSWERS.ROW_ID),
                        row.get(ANSWERS.NUM_VALUE),
                        row.get(ANSWERS.TEXT_VALUE),
                        row.get(ANSWERS.DATE_VALUE) == null ? null : row.get(ANSWERS.DATE_VALUE).toString()));
    }

    @Override
    public List<OptionCount> optionCounts(UUID surveyId, ResponseFilter filter) {
        return dsl.select(ANSWERS.QUESTION_ID, ANSWERS.OPTION_ID, DSL.count())
                .from(ANSWERS)
                .where(scope(surveyId, filter).and(ANSWERS.OPTION_ID.isNotNull()))
                .groupBy(ANSWERS.QUESTION_ID, ANSWERS.OPTION_ID)
                .fetch(row -> new OptionCount(row.value1(), row.value2(), row.value3().longValue()));
    }

    @Override
    public List<NumericValue> numericValues(UUID surveyId, ResponseFilter filter) {
        return dsl.select(ANSWERS.QUESTION_ID, ANSWERS.NUM_VALUE)
                .from(ANSWERS)
                .where(scope(surveyId, filter).and(ANSWERS.NUM_VALUE.isNotNull()))
                .fetch(row -> new NumericValue(row.value1(), row.value2()));
    }

    @Override
    public List<CellCount> matrixCells(UUID surveyId, ResponseFilter filter, UUID questionId) {
        return dsl.select(ANSWERS.ROW_ID, ANSWERS.OPTION_ID, DSL.count())
                .from(ANSWERS)
                .where(scope(surveyId, filter)
                        .and(ANSWERS.QUESTION_ID.eq(questionId))
                        .and(ANSWERS.ROW_ID.isNotNull()))
                .groupBy(ANSWERS.ROW_ID, ANSWERS.OPTION_ID)
                .fetch(row -> new CellCount(questionId, row.value1(), row.value2(), row.value3().longValue()));
    }

    @Override
    public List<BucketCount> submittedByBucket(
            UUID surveyId, ResponseFilter filter, Bucket bucket, String timezone) {
        // The bucket is computed in the survey's zone, because a day is the Tenant's day and not UTC's.
        var bucketStart = bucket == Bucket.DAY
                ? DSL.field("date_trunc('day', submitted_at at time zone {0})", Object.class, DSL.inline(zone(timezone)))
                : DSL.field(
                        "date_trunc('week', submitted_at at time zone {0})", Object.class, DSL.inline(zone(timezone)));
        return dsl.select(bucketStart.as("bucket"), DSL.count())
                .from(RESPONSES)
                .where(completed(surveyId, filter))
                .groupBy(DSL.field("bucket"))
                .orderBy(DSL.field("bucket"))
                .fetch(row -> new BucketCount(row.value1().toString(), row.value2().longValue()));
    }

    @Override
    public List<NpsValue> npsValues(UUID surveyId, ResponseFilter filter) {
        return dsl.select(RESPONSES.SCORE)
                .from(RESPONSES)
                .where(completed(surveyId, filter).and(RESPONSES.SCORE.isNotNull()))
                .fetch(row -> new NpsValue(row.value1()));
    }

    @Override
    public List<VisitRow> visits(UUID surveyId, Instant from, Instant to) {
        Condition window = VISITS.SURVEY_ID.eq(surveyId);
        if (from != null) {
            window = window.and(VISITS.OPENED_AT.ge(from));
        }
        if (to != null) {
            window = window.and(VISITS.OPENED_AT.lt(to));
        }
        return dsl.selectFrom(VISITS)
                .where(window)
                .fetch(row -> new VisitRow(
                        row.get(VISITS.ID),
                        row.get(VISITS.SOURCE),
                        row.get(VISITS.STATE),
                        row.get(VISITS.COUNTRY),
                        row.get(VISITS.DEVICE_CLASS),
                        row.get(VISITS.TRAFFIC_CLASS),
                        row.get(VISITS.RESPONSE_ID),
                        row.get(VISITS.OPENED_AT),
                        row.get(VISITS.LAST_ACTIVITY_AT)));
    }

    /** A completed response of this survey that matches the filter. */
    private static Condition completed(UUID surveyId, ResponseFilter filter) {
        return completed(surveyId)
                .and(window(filter))
                .and(filter.source() == null ? DSL.noCondition() : RESPONSES.SOURCE.eq(filter.source()))
                .and(filter.externalRef() == null ? DSL.noCondition() : RESPONSES.EXTERNAL_REF.eq(filter.externalRef()));
    }

    /**
     * The scope every read of the response table shares: this survey's submitted responses. A draft still being filled
     * in is not a response a report or a listing answers with.
     */
    private static Condition completed(UUID surveyId) {
        return RESPONSES.SURVEY_ID.eq(surveyId).and(RESPONSES.STATUS.eq("COMPLETED"));
    }

    /** The typed rows of a response that matches the filter, joined so the window and source apply. */
    private static Condition scope(UUID surveyId, ResponseFilter filter) {
        return ANSWERS.SURVEY_ID.eq(surveyId).and(ANSWERS.RESPONSE_ID.in(
                DSL.select(RESPONSES.ID).from(RESPONSES).where(completed(surveyId, filter))));
    }

    private static Condition window(ResponseFilter filter) {
        Condition window = DSL.noCondition();
        if (filter.from() != null) {
            window = window.and(RESPONSES.SUBMITTED_AT.ge(filter.from()));
        }
        if (filter.to() != null) {
            window = window.and(RESPONSES.SUBMITTED_AT.lt(filter.to()));
        }
        return window;
    }

    private static String zone(String timezone) {
        return timezone == null || timezone.isBlank() ? "UTC" : timezone;
    }

    private ResponseRow toRow(Record row) {
        return new ResponseRow(
                row.get(RESPONSES.ID),
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
