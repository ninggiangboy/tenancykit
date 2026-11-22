package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.catalog.application.session.command.MaterializeHorizonUseCase;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalog's create and resize of a session's pools reach the places: materializing a series inserts one row per place
 * of every pool, expanding the same horizon again adds none, and resizing a session moves that session's places only.
 */
class CapacitySessionPlacesTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MaterializeHorizonUseCase horizon;

    @Autowired
    CapacityUnitReader units;

    @Test
    void materializingASeriesCreatesThePlacesOfEveryPoolOnce() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "places");
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=2");

        String first = created.get("sessions").get(0).get("id").asString();
        String second = created.get("sessions").get(1).get("id").asString();
        assertThat(rowsOf(session, CAPACITY_UNITS)).as("two sessions of ten places each").isEqualTo(20);
        assertPlaces(session, first, "adult", 6);
        assertPlaces(session, first, "child", 4);
        assertPlaces(session, second, "adult", 6);
        assertPlaces(session, second, "child", 4);

        MaterializeHorizonUseCase.Result again =
                inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(again.sessions()).as("a second pass inserts no session").isZero();
        assertThat(rowsOf(session, CAPACITY_UNITS))
                .as("and therefore no place either")
                .isEqualTo(20);
        assertPlaces(session, first, "adult", 6);
    }

    @Test
    void cancellingAnOccurrenceNeitherRepeatsTheSessionNorItsPlaces() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "cancel");
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=2");
        String first = created.get("sessions").get(0).get("id").asString();

        deleteAs(session, "/api/v1/sessions/{sessionId}", null, first).andExpect(status().isNoContent());
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> horizon.execute()));

        assertThat(rowsOf(session, CAPACITY_UNITS)).as("the cancelled occurrence is not materialized again").isEqualTo(20);
        assertPlaces(session, first, "adult", 6);
    }

    @Test
    void resizingASessionsPoolsMovesThatSessionsPlacesOnly() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "resize");
        JsonNode created = createSeries(session, offering, "RRULE:FREQ=DAILY;COUNT=2");
        String first = created.get("sessions").get(0).get("id").asString();
        String second = created.get("sessions").get(1).get("id").asString();

        putAs(session, "/api/v1/sessions/{sessionId}/pools", """
                {"pools":[{"code":"adult","capacity":8},{"code":"child","capacity":2}]}
                """, second)
                .andExpect(status().isOk());

        assertPlaces(session, second, "adult", 8);
        assertPlaces(session, second, "child", 2);
        assertPlaces(session, first, "adult", 6);
        assertPlaces(session, first, "child", 4);

        getAs(session, "/api/v1/sessions/{sessionId}", second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools[0].code").value("adult"))
                .andExpect(jsonPath("$.pools[0].remaining").value(8))
                .andExpect(jsonPath("$.pools[1].code").value("child"))
                .andExpect(jsonPath("$.pools[1].remaining").value(2));

        putAs(session, "/api/v1/sessions/{sessionId}/pools", """
                {"pools":[{"code":"adult","capacity":10}]}
                """, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools.length()").value(1));

        assertPlaces(session, second, "adult", 10);
        assertPlaces(session, second, "child", 0);
        assertPlaces(session, first, "child", 4);
    }

    private void assertPlaces(Session session, String sessionId, String poolCode, int places) {
        UUID id = UUID.fromString(sessionId);
        assertThat(total(session, id, poolCode))
                .as("the pool " + poolCode + " of the session " + sessionId + " has that many places")
                .isEqualTo(places);
        assertThat(remaining(session, id, poolCode))
                .as("and every one of them is free")
                .isEqualTo(places);
        assertThat(committed(session, id, poolCode)).isZero();
    }

    private int total(Session session, UUID sessionId, String poolCode) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.total(sessionId, poolCode)));
    }

    private int remaining(Session session, UUID sessionId, String poolCode) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.remaining(sessionId, poolCode)));
    }

    private int committed(Session session, UUID sessionId, String poolCode) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> units.committed(sessionId, poolCode)));
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
                {"code":"%s","name":"Offering %s","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":90}
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
                {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["%s"],"durationMinutes":60,"capacity":10,
                 "pools":[{"code":"adult","capacity":6},{"code":"child","capacity":4}]}
                """.formatted(offeringId, dtstart, rule))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }
}
