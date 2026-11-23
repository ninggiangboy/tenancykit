package dev.ngb.booking.booking.adapter.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import dev.ngb.core.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The create endpoint's idempotency: one key returns one booking, the same key with another body is refused, and a
 * request with no key always creates.
 */
class BookingIdempotencyTest extends BookingApiTest {

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
    void theSameKeyAndBodyReturnsTheSameBookingAndAnotherBodyIsRefused() throws Exception {
        Session session = holdings();
        String offering = intervalOffering(session);
        Instant start = tomorrow(9);
        String body = body(offering, "cust-key", start);
        String other = body(offering, "cust-other", start);

        String id = idOf(create(session, body, "booking-key-1").andExpect(status().isCreated()));

        create(session, body, "booking-key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        create(session, other, "booking-key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("different request")));
    }

    @Test
    void aRequestWithNoKeyAlwaysCreates() throws Exception {
        Session session = holdings();
        String offering = intervalOffering(session);

        String first = idOf(create(session, body(offering, "cust-nokey", tomorrow(10)), null)
                .andExpect(status().isCreated()));
        String second = idOf(create(session, body(offering, "cust-nokey", tomorrow(11)), null)
                .andExpect(status().isCreated()));

        assertThat(second).as("an unkeyed create always inserts a new booking").isNotEqualTo(first);
    }

    private Session holdings() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD);
    }

    private ResultActions create(Session session, String body, String key) throws Exception {
        MockHttpServletRequestBuilder request = post("/system/v1/bookings");
        if (key != null) {
            request.header(HttpHeaders.IDEMPOTENCY_KEY, key);
        }
        return send(request, session, body, null);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "idem-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Idempotent","kind":"INTERVAL","durationMin":60,"slotStep":30,
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

    private UUID seedResource(Session session) throws Exception {
        String code = "idem-room-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static String body(String offeringId, String customerRef, Instant start) {
        return """
                {"offeringId":"%s","customerRef":"%s","start":"%s","durationMinutes":60}
                """.formatted(offeringId, customerRef, start);
    }

    private static String idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private static Instant tomorrow(int hour) {
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(hour));
    }
}
