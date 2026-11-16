package dev.ngb.booking.catalog.adapter.session.web;

import static dev.ngb.booking_jooq.Tables.SESSIONS;
import static dev.ngb.booking_jooq.Tables.SESSION_POOLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.catalog.application.session.command.MaterializeHorizonUseCase;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session endpoints over the real filter chain, the real database and a real tenant scope: creating a series and
 * the sessions it materializes, the conditional patch that regenerates only the future, cancelling one occurrence,
 * resizing its pools, and the listing contract.
 */
class SessionSeriesApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MaterializeHorizonUseCase horizon;

    @Test
    void createAnswers201WithTheSessionsInsideTheHorizon() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "create", "CAPACITY", 90);

        String body = postAs(session, "/api/v1/session-series", seriesBody(offering, "RRULE:FREQ=DAILY;COUNT=5"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.series.id").isString())
                .andExpect(jsonPath("$.series.offeringId").value(offering))
                .andExpect(jsonPath("$.series.zone").value("UTC"))
                .andExpect(jsonPath("$.series.durationMinutes").value(60))
                .andExpect(jsonPath("$.series.capacity").value(10))
                .andExpect(jsonPath("$.series.version").value(1))
                .andExpect(jsonPath("$.series.pools.length()").value(1))
                .andExpect(jsonPath("$.series.pools[0].code").value("default"))
                .andExpect(jsonPath("$.series.pools[0].capacity").value(10))
                .andExpect(jsonPath("$.series.pools[0].remaining").value(10))
                .andExpect(jsonPath("$.sessions.length()").value(5))
                .andExpect(jsonPath("$.sessions[0].seriesId").isString())
                .andExpect(jsonPath("$.sessions[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.sessions[0].capacity").value(10))
                .andExpect(jsonPath("$.sessions[0].pools[0].code").value("default"))
                .andExpect(jsonPath("$.untouchedSessions.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(JSON.readTree(body).get("sessions").size()).isEqualTo(5);
        assertThat(rowsOf(session, SESSIONS)).isEqualTo(5);
        assertThat(rowsOf(session, SESSION_POOLS)).isEqualTo(5);
    }

    @Test
    void anIntervalOfferingCannotCarryASessionSeries() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "interval", "INTERVAL", 30);

        postAs(session, "/api/v1/session-series", seriesBody(offering, "RRULE:FREQ=DAILY;COUNT=2"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowsOf(session, SESSIONS)).isZero();
    }

    @Test
    void anUnknownOfferingIsRefusedWith422() throws Exception {
        Session session = catalog();

        postAs(session, "/api/v1/session-series", seriesBody(UUID.randomUUID().toString(), "RRULE:FREQ=DAILY;COUNT=2"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aRuleFinerThanDailyIsRefusedForASeries() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "hourly", "CAPACITY", 90);

        postAs(session, "/api/v1/session-series", seriesBody(offering, "RRULE:FREQ=HOURLY;COUNT=3"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("invalid-recurrence"));
    }

    @Test
    void aPatchWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "conditional", "CAPACITY", 90);
        String seriesId = idOf(createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=3"));

        patchAs(session, "/api/v1/session-series/{seriesId}", "{\"durationMinutes\":90}", null, seriesId)
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void aStalePatchIsRefusedWith412() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "stale", "CAPACITY", 90);
        String seriesId = idOf(createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=3"));

        patchAs(session, "/api/v1/session-series/{seriesId}", "{\"durationMinutes\":90}", "\"1\"", seriesId)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        patchAs(session, "/api/v1/session-series/{seriesId}", "{\"durationMinutes\":120}", "\"1\"", seriesId)
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void aPatchRegeneratesOnlyTheFutureAndLeavesThemConsistent() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "patch", "CAPACITY", 90);
        String seriesId = idOf(createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=5"));
        assertThat(rowsOf(session, SESSIONS)).isEqualTo(5);

        String body = patchAs(session, "/api/v1/session-series/{seriesId}", "{\"durationMinutes\":90}", "\"1\"", seriesId)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.series.durationMinutes").value(90))
                .andExpect(jsonPath("$.series.version").value(2))
                .andExpect(jsonPath("$.sessions.length()").value(5))
                .andExpect(jsonPath("$.untouchedSessions.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        for (JsonNode materialized : JSON.readTree(body).get("sessions")) {
            assertThat(Duration.between(
                            Instant.parse(materialized.get("startsAt").asString()),
                            Instant.parse(materialized.get("endsAt").asString()))
                    .toMinutes())
                    .as("every regenerated session runs for the new duration")
                    .isEqualTo(90);
        }
        assertThat(rowsOf(session, SESSIONS)).as("a patch replaces the future, it does not add to it").isEqualTo(5);
    }

    @Test
    void aPatchToArchivedStopsMaterializing() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "archived", "CAPACITY", 90);
        String seriesId = idOf(createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=3"));

        patchAs(session, "/api/v1/session-series/{seriesId}", "{\"status\":\"ARCHIVED\"}", "\"1\"", seriesId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0));

        assertThat(rowsOf(session, SESSIONS)).as("archiving leaves the sessions it already materialized").isEqualTo(3);

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));
        assertThat(rowsOf(session, SESSIONS)).isEqualTo(3);
    }

    @Test
    void cancellingIs204AndTheOccurrenceIsNotMaterializedAgain() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "cancel", "CAPACITY", 90);
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=3");
        String sessionId = created.get("sessions").get(0).get("id").asString();

        deleteAs(session, "/api/v1/sessions/{sessionId}", null, sessionId).andExpect(status().isNoContent());

        getAs(session, "/api/v1/sessions/{sessionId}", sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(rowsOf(session, SESSIONS)).isEqualTo(3);
        list(session, "filter[status][eq]", "SCHEDULED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void cancellingAnUnknownSessionIs404() throws Exception {
        Session session = catalog();

        deleteAs(session, "/api/v1/sessions/{sessionId}", null, UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    @Test
    void resizingPoolsAnswersTheSessionWithItsNewPools() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "resize", "CAPACITY", 90);
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=2");
        String sessionId = created.get("sessions").get(0).get("id").asString();

        putAs(session, "/api/v1/sessions/{sessionId}/pools", """
                {"pools":[{"code":"adult","capacity":6},{"code":"child","capacity":4}]}
                """, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools.length()").value(2))
                .andExpect(jsonPath("$.pools[0].code").value("adult"))
                .andExpect(jsonPath("$.pools[0].capacity").value(6))
                .andExpect(jsonPath("$.pools[0].remaining").value(6))
                .andExpect(jsonPath("$.pools[1].code").value("child"))
                .andExpect(jsonPath("$.pools[1].capacity").value(4));

        getAs(session, "/api/v1/sessions/{sessionId}", sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools.length()").value(2))
                .andExpect(jsonPath("$.pools[0].code").value("adult"));
    }

    @Test
    void poolsThatOfferMorePlacesThanTheSessionAreRefusedWith422() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "overfull", "CAPACITY", 90);
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=2");
        String sessionId = created.get("sessions").get(0).get("id").asString();

        putAs(session, "/api/v1/sessions/{sessionId}/pools", """
                {"pools":[{"code":"adult","capacity":11}]}
                """, sessionId)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        putAs(session, "/api/v1/sessions/{sessionId}/pools", """
                {"pools":[{"code":"adult","capacity":-1}]}
                """, sessionId)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theSessionListFiltersAndPages() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "listing", "CAPACITY", 90);
        String other = createOffering(session, "listing-other", "CAPACITY", 90);
        createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=4");
        createSeries(session, other, "RRULE:FREQ=DAILY;COUNT=3");

        list(session, "filter[offering_id][eq]", offering, "limit", "2", "page_info", "cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].offeringId").value(offering))
                .andExpect(jsonPath("$.data[1].offeringId").value(offering))
                .andExpect(jsonPath("$.page.next_cursor").isString());

        list(session, "filter[offering_id][eq]", other, "filter[status][eq]", "SCHEDULED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].status").value("SCHEDULED"));

        list(session, "sort", "-starts_at", "limit", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void anotherTenantsSessionIs404() throws Exception {
        Session one = catalog();
        Session other = catalog();
        String offering = createOffering(one, "private", "CAPACITY", 90);
        JsonNode created = createSeries(one, offering, "RRULE:FREQ=DAILY;COUNT=2");
        String sessionId = created.get("sessions").get(0).get("id").asString();

        getAs(other, "/api/v1/sessions/{sessionId}", sessionId).andExpect(status().isNotFound());
        deleteAs(other, "/api/v1/sessions/{sessionId}", null, sessionId).andExpect(status().isNotFound());
    }

    private Session catalog() {
        return tenant(
                BookingPermissions.OFFERING_READ,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_READ,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_MANAGE);
    }

    private String createOffering(Session session, String code, String kind, int horizonDays) throws Exception {
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Offering %s","kind":"%s","durationMin":60,"slotStep":60,"horizonDays":%d}
                """.formatted(code, code, kind, horizonDays))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private JsonNode createSeries(Session session, String offeringId, String rule) throws Exception {
        String body = postAs(session, "/api/v1/session-series", seriesBody(offeringId, rule))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private static String idOf(JsonNode created) {
        return created.get("series").get("id").asString();
    }

    private static String seriesBody(String offeringId, String rule) {
        String dtstart = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();
        return """
                {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["%s"],"durationMinutes":60,"capacity":10}
                """
                .formatted(offeringId, dtstart, rule);
    }

    private ResultActions list(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/sessions")
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }
}
