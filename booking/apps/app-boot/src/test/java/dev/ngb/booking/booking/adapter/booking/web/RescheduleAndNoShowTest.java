package dev.ngb.booking.booking.adapter.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moving a booking and marking a missed one: a refused move leaves the booking and its claims where they were, a shift
 * inside the booking's own range succeeds, and a no-show is only a confirmed booking whose start has passed.
 */
class RescheduleAndNoShowTest extends BookingApiTest {

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
    void aRescheduleToATakenPlaceLeavesTheBookingUnchanged() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String moving = hold(session, offering, "moving", tomorrow(10));
        String taken = hold(session, offering, "taken", tomorrow(11));

        postAs(session, "/api/v1/bookings/{bookingId}/reschedule", """
                {"start":"%s","durationMinutes":60}
                """.formatted(tomorrow(11)), moving)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("slot-taken"));

        getAs(session, "/api/v1/bookings/{bookingId}", moving)
                .andExpect(jsonPath("$.startsAt").value(tomorrow(10).toString()))
                .andExpect(jsonPath("$.version").value(1));
        getAs(session, "/api/v1/bookings/{bookingId}", taken)
                .andExpect(jsonPath("$.startsAt").value(tomorrow(11).toString()));
    }

    @Test
    void aShiftInsideTheBookingsOwnRangeSucceeds() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering, "shift", tomorrow(10));

        postAs(session, "/api/v1/bookings/{bookingId}/reschedule", """
                {"start":"%s","durationMinutes":60}
                """.formatted(tomorrow(10).plus(Duration.ofMinutes(15))), booking)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value(tomorrow(10).plus(Duration.ofMinutes(15)).toString()))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void aNoShowNeedsAConfirmedBookingWhoseStartHasPassed() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String future = hold(session, offering, "future", tomorrow(10));
        postAs(session, "/api/v1/bookings/{bookingId}/confirm", null, future).andExpect(status().isOk());
        postAs(session, "/api/v1/bookings/{bookingId}/no-show", null, future)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("booking-state"));

        String held = hold(session, offering, "held", yesterday(10));
        postAs(session, "/api/v1/bookings/{bookingId}/no-show", null, held)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("booking-state"));

        String missed = hold(session, offering, "missed", yesterday(8));
        postAs(session, "/api/v1/bookings/{bookingId}/confirm", null, missed).andExpect(status().isOk());
        postAs(session, "/api/v1/bookings/{bookingId}/no-show", null, missed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.version").value(3));
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM,
                BookingPermissions.BOOKING_MANAGE);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "move-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Move","kind":"INTERVAL","durationMin":60,"slotStep":15,"horizonDays":30}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        UUID resource = resource(session);
        putAs(session, "/api/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private UUID resource(Session session) throws Exception {
        String code = "move-res-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/resources", """
                {"code":"%s","name":"Move","zone":"UTC"}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/api/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }

    private String hold(Session session, String offeringId, String customerRef, Instant start) throws Exception {
        return JSON.readTree(postAs(session, "/api/v1/bookings", """
                        {"offeringId":"%s","customerRef":"%s","start":"%s","durationMinutes":60}
                        """.formatted(offeringId, customerRef, start))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private static Instant tomorrow(int hour) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1)).plus(Duration.ofHours(hour));
    }

    private static Instant yesterday(int hour) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(1)).plus(Duration.ofHours(hour));
    }
}
