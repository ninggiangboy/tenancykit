package dev.ngb.booking.catalog.application.session.service;

import static dev.ngb.booking_jooq.Tables.SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.catalog.application.session.command.MaterializeHorizonUseCase;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What makes the horizon safe to expand again: the pair of a series and a start instant is unique, so a second pass
 * inserts nothing, and a cancelled occurrence is excluded from the series, so it is not recreated even when its row is
 * gone.
 *
 * <p>The passes run the same use case the daily job runs, in the tenant's own scope, because a materialization outside
 * a tenant scope would see nothing at all under row-level security.
 */
class SessionMaterializationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MaterializeHorizonUseCase horizon;

    @Test
    void expandingTheSameHorizonTwiceInsertsNoDuplicate() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "twice");
        createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=5");

        assertThat(rowsOf(session, SESSIONS)).isEqualTo(5);

        MaterializeHorizonUseCase.Result second = inTenant(
                session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(second.series()).isEqualTo(1);
        assertThat(second.sessions()).as("a second pass inserts nothing").isZero();
        assertThat(rowsOf(session, SESSIONS)).isEqualTo(5);
    }

    @Test
    void anExcludedOccurrenceIsNotRecreated() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "excluded");
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=5");
        Instant cancelled = firstStart(created);
        String sessionId = created.get("sessions").get(0).get("id").asString();

        deleteAs(session, "/api/v1/sessions/{sessionId}", null, sessionId).andExpect(status().isNoContent());

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.deleteFrom(SESSIONS).execute();
        }));

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(rowsOf(session, SESSIONS))
                .as("the excluded occurrence is the one that is not recreated")
                .isEqualTo(4);
        assertThat(startInstants(session)).doesNotContain(cancelled);
    }

    @Test
    void cancellingKeepsTheRowAndExcludesTheOccurrenceFromItsSeries() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "cancelled");
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=3");
        String sessionId = created.get("sessions").get(0).get("id").asString();

        deleteAs(session, "/api/v1/sessions/{sessionId}", null, sessionId).andExpect(status().isNoContent());

        getAs(session, "/api/v1/sessions/{sessionId}", sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String series = getAs(session, "/api/v1/session-series")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> rule = new ArrayList<>();
        JSON.readTree(series)
                .get("data")
                .get(0)
                .get("rule")
                .valueStream()
                .forEach(line -> rule.add(line.asString()));
        assertThat(rule).anyMatch(line -> line.startsWith("EXDATE;TZID=UTC:"));

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(rowsOf(session, SESSIONS)).isEqualTo(3);
    }

    private Session catalog() {
        return tenant(
                BookingPermissions.OFFERING_READ,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_READ,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_MANAGE);
    }

    private String createOffering(Session session, String code) throws Exception {
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"cap-%s","name":"Capacity %s","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":90}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private JsonNode createSeries(Session session, String offeringId, String rule) throws Exception {
        String dtstart = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();
        String body = postAs(session, "/api/v1/session-series", """
                {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["%s"],"durationMinutes":60,"capacity":10}
                """.formatted(offeringId, dtstart, rule))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private static Instant firstStart(JsonNode created) {
        return Instant.parse(created.get("sessions").get(0).get("startsAt").asString());
    }

    private List<Instant> startInstants(Session session) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(
                        () -> List.copyOf(dsl.select(SESSIONS.STARTS_AT).from(SESSIONS).fetch(SESSIONS.STARTS_AT))));
    }
}
