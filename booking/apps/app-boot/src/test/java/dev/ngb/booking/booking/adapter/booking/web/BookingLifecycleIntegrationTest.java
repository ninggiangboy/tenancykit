package dev.ngb.booking.booking.adapter.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The booking lifecycle over the real filter chain, the real database and a real tenant scope: holding a range,
 * reading it, confirming it, extending a hold once, cancelling it, the list contract, and a capacity hold from a
 * session's pool.
 */
class BookingLifecycleIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ALL_DAY_HOURS = """
            {"hours":[{"weekday":1,"opens":"00:00","closes":"24:00"},
                       {"weekday":2,"opens":"00:00","closes":"24:00"},
                       {"weekday":3,"opens":"00:00","closes":"24:00"},
                       {"weekday":4,"opens":"00:00","closes":"24:00"},
                       {"weekday":5,"opens":"00:00","closes":"24:00"},
                       {"weekday":6,"opens":"00:00","closes":"24:00"},
                       {"weekday":7,"opens":"00:00","closes":"24:00"}]}
            """;

    @Test
    void createReadConfirmAndCancelAnswerTheStatesAndTags() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering, "cust-a", tomorrow(10));

        getAs(session, "/api/v1/bookings/{bookingId}", booking)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.offeringKind").value("INTERVAL"))
                .andExpect(jsonPath("$.customerRef").value("cust-a"))
                .andExpect(jsonPath("$.resources.length()").value(1));

        postAs(session, "/api/v1/bookings/{bookingId}/confirm", null, booking)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        postAs(session, "/api/v1/bookings/{bookingId}/cancel", null, booking)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void aHoldExtendsOnceAndThenIsRefused() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering, "cust-extend", tomorrow(11));

        Instant extended = Instant.parse(JSON.readTree(postAs(
                                session, "/api/v1/bookings/{bookingId}/extend", "{\"seconds\":600}", booking)
                        .andExpect(status().isOk())
                        .andExpect(header().string("ETag", "\"2\""))
                        .andExpect(jsonPath("$.extendedSeconds").value(600))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("holdExpiresAt")
                .asString());

        postAs(session, "/api/v1/bookings/{bookingId}/extend", "{\"seconds\":600}", booking)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("booking-state"));

        getAs(session, "/api/v1/bookings/{bookingId}", booking)
                .andExpect(jsonPath("$.holdExpiresAt").value(extended.toString()))
                .andExpect(jsonPath("$.extendedSeconds").value(600));
    }

    @Test
    void anExtensionPastTheCapIsRefused() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering, "cust-cap", tomorrow(12));

        postAs(session, "/api/v1/bookings/{bookingId}/extend", "{\"seconds\":3600}", booking)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EXTENSION_CAP"));
    }

    @Test
    void theListFiltersByStatusAndPagesByCursor() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        hold(session, offering, "cust-1", tomorrow(13));
        hold(session, offering, "cust-2", tomorrow(14));
        hold(session, offering, "cust-3", tomorrow(15));

        String firstPage = list(
                        session,
                        "filter[status][eq]", "HELD",
                        "limit", "1",
                        "page_info", "cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("HELD"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cursor = JSON.readTree(firstPage).get("page").get("next_cursor").asString();
        assertThat(cursor).as("the first page is not the last one").isNotBlank();

        list(session,
                        "filter[status][eq]", "HELD",
                        "limit", "1",
                        "page_info", "cursor",
                        "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void anUnknownBookingIs404() throws Exception {
        Session session = lifecycle();

        getAs(session, "/api/v1/bookings/{bookingId}", UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void aCapacityOfferingHoldsPlacesFromASessionPool() throws Exception {
        Session session = lifecycle();
        String offering = capacityOffering(session);
        String sessionId = materializeSession(session, offering);

        postAs(session, "/api/v1/bookings", """
                {"offeringId":"%s","customerRef":"cust-capacity","sessionId":"%s","poolCode":"default","qty":1}
                """.formatted(offering, sessionId))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.offeringKind").value("CAPACITY"))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.poolCode").value("default"))
                .andExpect(jsonPath("$.resources.length()").value(0));
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM);
    }

    private String intervalOffering(Session session) throws Exception {
        String offering = createOffering(session, "INTERVAL", 60, 30, 30);
        putAs(session, "/api/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(seedResource(session)), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private String capacityOffering(Session session) throws Exception {
        return createOffering(session, "CAPACITY", 60, 60, 90);
    }

    private String createOffering(Session session, String kind, int durationMin, int slotStep, int horizonDays)
            throws Exception {
        String code = "book-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Booking %s","kind":"%s","durationMin":%d,"slotStep":%d,"horizonDays":%d}
                """.formatted(code, code, kind, durationMin, slotStep, horizonDays))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private String materializeSession(Session session, String offeringId) throws Exception {
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(18)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toString();
        String body = postAs(session, "/api/v1/session-series", """
                {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                 "durationMinutes":60,"capacity":10}
                """.formatted(offeringId, dtstart))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("sessions").get(0).get("id").asString();
    }

    private String hold(Session session, String offeringId, String customerRef, Instant start) throws Exception {
        String body = postAs(session, "/api/v1/bookings", """
                {"offeringId":"%s","customerRef":"%s","start":"%s","durationMinutes":60}
                """.formatted(offeringId, customerRef, start))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private UUID seedResource(Session session) throws Exception {
        String code = "room-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/resources", """
                {"code":"%s","name":"Room %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/api/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }

    /** A whole hour of the next UTC day, inside the all-day working hours. */
    private static Instant tomorrow(int hour) {
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(hour));
    }

    private ResultActions list(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/bookings");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return send(request, session, null, null);
    }
}
