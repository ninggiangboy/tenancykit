package dev.ngb.booking.boot.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** The invariant check passes on a clean tenant and on the claims a hold writes. */
class InvariantCheckTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    InvariantCheck invariants;

    @Test
    void aCleanTenantAndItsHoldHaveNoViolations() throws Exception {
        Session session = tenant(
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_HOLD);
        List<String> before = inTenant(
                session.tenant(), () -> unitOfWork.transactional(invariants::violations));
        assertThat(before).isEmpty();

        String code = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/api/v1/offerings", """
                        {"code":"%s","name":"Invariant","kind":"CAPACITY","durationMin":60,"slotStep":60,
                         "horizonDays":30}
                        """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0)
                .toString();
        String sessionId = JSON.readTree(postAs(session, "/api/v1/session-series", """
                        {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                         "durationMinutes":60,"capacity":2}
                        """.formatted(offering, dtstart))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("sessions").get(0).get("id").asString();
        postAs(session, "/api/v1/bookings", """
                        {"offeringId":"%s","customerRef":"cust-inv","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(offering, sessionId))
                .andExpect(status().isCreated());

        List<String> after = inTenant(
                session.tenant(), () -> unitOfWork.transactional(invariants::violations));
        assertThat(after).as("a hold owns its unit and the booking is HELD").isEmpty();
    }
}
