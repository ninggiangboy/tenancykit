package dev.ngb.booking.booking.adapter.series.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A repeating booking: one hold per occurrence, all or nothing or skipping conflicts, a shared rule across a DST
 * change, and confirm and cancel acting on every occurrence.
 */
class BookingSeriesTest extends BookingApiTest {

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
    void allOrNothingBooksEveryOccurrenceOrNone() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        Instant first = tomorrow(9);

        createSeries(session, offering, "series-a", first, "RRULE:FREQ=WEEKLY;COUNT=4", "ALL_OR_NOTHING")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurrences.length()").value(4))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(occurrenceCount(session)).isEqualTo(4);

        Instant blocked = tomorrow(10);
        hold(session, offering, "blocker", blocked);
        createSeries(session, offering, "series-b", blocked, "RRULE:FREQ=WEEKLY;COUNT=4", "ALL_OR_NOTHING")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("series-conflict"))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].startsAt").value(blocked.toString()));
        assertThat(occurrenceCount(session))
                .as("an all-or-nothing series that conflicts books nothing")
                .isEqualTo(5);
    }

    @Test
    void skipConflictsBooksTheFreeOccurrencesAndReportsTheBlockedOne() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        Instant first = tomorrow(12);
        hold(session, offering, "blocker", first);

        String body = createSeries(session, offering, "series-skip", first, "RRULE:FREQ=WEEKLY;COUNT=4",
                        "SKIP_CONFLICTS")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurrences.length()").value(3))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].startsAt").value(first.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String series = JSON.readTree(body).get("id").asString();

        getAs(session, "/api/v1/series/{seriesId}", series)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences.length()").value(3));
    }

    @Test
    void aWeeklySeriesKeepsItsLocalTimeAcrossADstChange() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String dtstart = "2027-03-01T09:00";
        String body = createSeriesWithDtstart(
                        session, offering, "dst", "America/New_York", dtstart,
                        "RRULE:FREQ=WEEKLY;COUNT=3", "ALL_OR_NOTHING")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode occurrences = JSON.readTree(body).get("occurrences");
        ZoneId zone = ZoneId.of("America/New_York");
        for (JsonNode occurrence : occurrences) {
            Instant start = Instant.parse(occurrence.get("startsAt").asString());
            assertThat(start.atZone(zone).toLocalTime())
                    .as("the same wall-clock time is booked after the clocks move")
                    .isEqualTo(java.time.LocalTime.of(9, 0));
        }
    }

    @Test
    void confirmAndCancelActOnEveryFutureOccurrence() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String body = createSeries(session, offering, "series-life", tomorrow(14),
                        "RRULE:FREQ=DAILY;COUNT=3", "ALL_OR_NOTHING")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String series = JSON.readTree(body).get("id").asString();

        postAs(session, "/api/v1/series/{seriesId}/confirm", null, series)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.occurrences[2].status").value("CONFIRMED"));

        postAs(session, "/api/v1/series/{seriesId}/cancel", null, series)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.occurrences[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.occurrences[2].status").value("CANCELLED"));
    }

    private ResultActions createSeries(
            Session session, String offering, String customer, Instant first, String rule, String mode)
            throws Exception {
        return createSeries(
                session, offering, customer, first, "UTC", rule, mode);
    }

    private ResultActions createSeries(
            Session session,
            String offering,
            String customer,
            Instant first,
            String zone,
            String rule,
            String mode)
            throws Exception {
        String dtstart = LocalDateTime.ofInstant(first, ZoneId.of(zone))
                .withSecond(0)
                .withNano(0)
                .toString();
        return createSeriesWithDtstart(session, offering, customer, zone, dtstart, rule, mode);
    }

    private ResultActions createSeriesWithDtstart(
            Session session,
            String offering,
            String customer,
            String zone,
            String dtstart,
            String rule,
            String mode)
            throws Exception {
        return postAs(session, "/api/v1/series", """
                {"offeringId":"%s","customerRef":"%s","zone":"%s","dtstart":"%s","rule":["%s"],
                 "durationMinutes":60,"mode":"%s"}
                """.formatted(offering, customer, zone, dtstart, rule, mode));
    }

    private long occurrenceCount(Session session) throws Exception {
        String body = getAs(session, "/api/v1/bookings?limit=100")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("data").size();
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM,
                BookingPermissions.SERIES_READ,
                BookingPermissions.SERIES_MANAGE);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "series-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Series","kind":"INTERVAL","durationMin":60,"slotStep":15,"horizonDays":400}
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
        String code = "series-res-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/resources", """
                {"code":"%s","name":"Series","zone":"UTC"}
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
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(hour));
    }
}
