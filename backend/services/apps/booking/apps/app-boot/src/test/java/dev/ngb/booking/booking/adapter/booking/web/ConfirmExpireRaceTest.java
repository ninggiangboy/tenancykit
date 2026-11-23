package dev.ngb.booking.booking.adapter.booking.web;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;
import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.Condition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * One booking confirmed by many callers at once: the row lock makes exactly one of them change the booking, and every
 * other answer is the idempotent replay of the state it already has.
 *
 * <p>The database is what decides: the confirmation reads the booking under its row lock and appends its event only
 * when it moved the booking, so one event and one committed claim are the whole effect of a hundred requests.
 */
class ConfirmExpireRaceTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int CONFIRMS = 100;

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
    void aHundredParallelConfirmsMoveTheBookingOnce() throws Exception {
        Session session = race();
        String offering = intervalOffering(session);
        String booking = hold(session, offering);
        UUID bookingId = UUID.fromString(booking);

        List<Future<Integer>> answers = new ArrayList<>(CONFIRMS);
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONFIRMS; i++) {
                answers.add(callers.submit(() -> {
                    return mvc.perform(post("/system/v1/bookings/{bookingId}/confirm", booking)
                                    .header("Host", session.host())
                                    .header("Authorization", "Bearer " + session.token()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            for (Future<Integer> answer : answers) {
                assertThat(answer.get())
                        .as("every confirm answers the booking it already holds")
                        .isEqualTo(200);
            }
        }

        assertThat(outbox(session, "booking.confirmed"))
                .as("one confirmation announces one fact")
                .isEqualTo(1);
        assertThat(countBookings(session, BOOKINGS.ID.eq(bookingId).and(BOOKINGS.STATUS.eq("CONFIRMED"))))
                .isEqualTo(1);
        assertThat(countBookings(session, BOOKINGS.ID.eq(bookingId).and(BOOKINGS.VERSION.eq(2L))))
                .as("the version rose exactly once")
                .isEqualTo(1);
        assertThat(countClaims(session, INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId)))
                .as("the hold left exactly one claim")
                .isEqualTo(1);
        assertThat(countClaims(
                        session, INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId).and(INTERVAL_CLAIMS.EXPIRES_AT.isNull())))
                .as("the claim was committed, not left as an orphan hold")
                .isEqualTo(1);
    }

    private Session race() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "race-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Race","kind":"INTERVAL","durationMin":60,"slotStep":30,"horizonDays":30}
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
                        {"offeringId":"%s","customerRef":"cust-race","start":"%s","durationMinutes":60}
                        """.formatted(offeringId, start))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private UUID seedResource(Session session) throws Exception {
        String code = "race-room-" + UUID.randomUUID().toString().substring(0, 8);
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

    private long outbox(Session session, String type) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(OUTBOX, OUTBOX.TYPE.eq(type))));
    }

    private long countBookings(Session session, Condition condition) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(BOOKINGS, condition)));
    }

    private long countClaims(Session session, Condition condition) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(INTERVAL_CLAIMS, condition)));
    }
}
