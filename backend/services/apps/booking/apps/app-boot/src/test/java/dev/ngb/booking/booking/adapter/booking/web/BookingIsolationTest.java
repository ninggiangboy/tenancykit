package dev.ngb.booking.booking.adapter.booking.web;

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
 * A booking is visible to its own tenant and to no other: another tenant's read, confirm and cancel all answer not
 * found rather than forbidden, so the existence of the booking is not leaked.
 */
class BookingIsolationTest extends BookingApiTest {

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
    void anotherTenantsBookingIsNotFoundForEveryCall() throws Exception {
        Session one = lifecycle();
        Session other = lifecycle();
        String offering = intervalOffering(one);
        String booking = hold(one, offering);

        getAs(other, "/system/v1/bookings/{bookingId}", booking).andExpect(status().isNotFound());
        postAs(other, "/system/v1/bookings/{bookingId}/confirm", null, booking).andExpect(status().isNotFound());
        postAs(other, "/system/v1/bookings/{bookingId}/cancel", null, booking).andExpect(status().isNotFound());

        getAs(one, "/system/v1/bookings/{bookingId}", booking)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                        .value("HELD"));
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
        String code = "iso-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Isolation","kind":"INTERVAL","durationMin":60,"slotStep":30,
                         "horizonDays":30}
                        """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        UUID resource = seedResource(session);
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private String hold(Session session, String offeringId) throws Exception {
        Instant start = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(9));
        return JSON.readTree(postAs(session, "/system/v1/bookings", """
                        {"offeringId":"%s","customerRef":"cust-iso","start":"%s","durationMinutes":60}
                        """.formatted(offeringId, start))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private UUID seedResource(Session session) throws Exception {
        String code = "iso-room-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Room %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }
}
