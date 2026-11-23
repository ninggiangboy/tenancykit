package dev.ngb.booking.booking.adapter.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two offerings that name the same two resources in opposite order, booked at the same times by many callers.
 *
 * <p>The claims of one booking are inserted in ascending resource id, so two bookings that would otherwise take the
 * resources in opposite order meet the same row first; every answer is a hold or {@code slot-taken}, and none is a
 * deadlock escaping to the caller.
 */
class CrossingResourceSetsDeadlockTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int TIMES = 100;

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
    void crossingRequirementSetsNeverDeadlock() throws Exception {
        Session session = race();
        UUID left = resource(session);
        UUID right = resource(session);
        String leftThenRight = offering(session, left, right);
        String rightThenLeft = offering(session, right, left);

        List<Future<Integer>> answers = new ArrayList<>(TIMES * 2);
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < TIMES; index++) {
                Instant start = tomorrow(9).plus(Duration.ofHours(index));
                answers.add(submit(callers, session, leftThenRight, "left-" + index, start));
                answers.add(submit(callers, session, rightThenLeft, "right-" + index, start));
            }
            int held = 0;
            int taken = 0;
            for (Future<Integer> answer : answers) {
                int status = answer.get();
                assertThat(status)
                        .as("a crossing booking answers a hold or a conflict, never a server error")
                        .isIn(201, 409);
                if (status == 201) {
                    held++;
                } else {
                    taken++;
                }
            }
            assertThat(held).as("one of each crossing pair wins its time").isEqualTo(TIMES);
            assertThat(taken).as("the other loses without a deadlock").isEqualTo(TIMES);
        }
    }

    private Future<Integer> submit(
            ExecutorService callers, Session session, String offering, String customerRef, Instant start) {
        return callers.submit(() -> mvc.perform(post("/system/v1/bookings")
                        .contentType("application/json")
                        .content("""
                                {"offeringId":"%s","customerRef":"%s","start":"%s","durationMinutes":60}
                                """.formatted(offering, customerRef, start))
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + session.token()))
                .andReturn()
                .getResponse()
                .getStatus());
    }

    private Session race() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD);
    }

    private UUID resource(Session session) throws Exception {
        String code = "cross-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Resource %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        return resource;
    }

    private String offering(Session session, UUID first, UUID second) throws Exception {
        String code = "cross-off-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/offerings", """
                {"code":"%s","name":"Crossing %s","kind":"INTERVAL","durationMin":60,"slotStep":30,
                 "horizonDays":30}
                """.formatted(code, code))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"first","resourceId":"%s"},
                                 {"slotCode":"second","resourceId":"%s"}]}
                """.formatted(first, second), offering)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        return offering;
    }

    private static Instant tomorrow(int hour) {
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(hour));
    }
}
