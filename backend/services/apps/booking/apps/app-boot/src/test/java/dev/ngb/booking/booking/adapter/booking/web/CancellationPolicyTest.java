package dev.ngb.booking.booking.adapter.booking.web;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * A cancellation reports which window it fell in by the notice it gave: the longest window whose threshold the
 * remaining time still meets, {@code NONE} when none does, and {@code AFTER_START} once the start has passed.
 */
class CancellationPolicyTest extends BookingApiTest {

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
    void thePolicyCodeFollowsTheNoticeGiven() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        putAs(session, "/system/v1/offerings/{offeringId}/cancellation-windows", """
                {"windows":[{"minutesBeforeStart":1440,"code":"full-refund"},
                            {"minutesBeforeStart":60,"code":"late"}]}
                """, offering)
                .andExpect(status().isOk());

        confirmed(session, offering, "far", Instant.now().plus(Duration.ofDays(3)))
                .andExpect(jsonPath("$.policyCode").value("full-refund"));
        confirmed(session, offering, "tomorrow", Instant.now().plus(Duration.ofHours(23)))
                .andExpect(jsonPath("$.policyCode").value("late"));
        confirmed(session, offering, "soon", Instant.now().plus(Duration.ofMinutes(30)))
                .andExpect(jsonPath("$.policyCode").value("NONE"));
        confirmed(session, offering, "past", yesterday(8))
                .andExpect(jsonPath("$.policyCode").value("AFTER_START"));

        String held = hold(session, offering, "held", tomorrow(12));
        postAs(session, "/system/v1/bookings/{bookingId}/cancel", null, held)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value("NONE"));
    }

    private org.springframework.test.web.servlet.ResultActions confirmed(
            Session session, String offering, String customer, Instant start) throws Exception {
        String booking = hold(session, offering, customer, start);
        postAs(session, "/system/v1/bookings/{bookingId}/confirm", null, booking)
                .andExpect(status().isOk());
        return postAs(session, "/system/v1/bookings/{bookingId}/cancel", null, booking)
                .andExpect(status().isOk());
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "policy-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/offerings", """
                {"code":"%s","name":"Policy","kind":"INTERVAL","durationMin":60,"slotStep":15,"horizonDays":30}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        UUID resource = resource(session);
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private UUID resource(Session session) throws Exception {
        String code = "policy-res-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Policy","zone":"UTC"}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }

    private String hold(Session session, String offeringId, String customerRef, Instant start) throws Exception {
        return JSON.readTree(postAs(session, "/system/v1/bookings", """
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
