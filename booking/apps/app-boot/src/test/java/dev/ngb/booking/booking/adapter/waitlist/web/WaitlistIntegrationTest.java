package dev.ngb.booking.booking.adapter.waitlist.web;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.booking.application.waitlist.command.OfferWaitlistUseCase;
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
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The waitlist end to end: joining a full target, being offered an ordinary hold when a place frees, following that
 * hold to accepted or lapsed, and leaving. An entry that does not fit keeps its position while a later one is offered.
 */
class WaitlistIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    OfferWaitlistUseCase offers;

    @Autowired
    ExpireHoldsUseCase expirations;

    @Test
    void joiningAFullTargetWaitsOnceAndLeavingRemovesTheEntry() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        fill(session, capacity, "holder");

        String first = join(session, capacity, "cust-a", 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String entry = JSON.readTree(first).get("id").asString();

        join(session, capacity, "cust-b", 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1));
        join(session, capacity, "cust-a", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_WAITING"));

        Capacity free = capacity(session, 1);
        join(session, free, "cust-c", 1)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("TARGET_AVAILABLE"));

        deleteAs(session, "/api/v1/waitlist/{entryId}", null, entry).andExpect(status().isNoContent());
        getAs(session, "/api/v1/waitlist/{entryId}", entry)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void aFreedPlaceOffersTheOldestEntryAndConfirmingAcceptsIt() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        String holder = fill(session, capacity, "holder");
        String first = entryId(join(session, capacity, "cust-first", 1).andExpect(status().isCreated()));
        entryId(join(session, capacity, "cust-second", 1).andExpect(status().isCreated()));

        postAs(session, "/api/v1/bookings/{bookingId}/cancel", null, holder).andExpect(status().isOk());
        assertThat(inTenant(session.tenant(), offers::execute)).isEqualTo(1);

        String offered = getAs(session, "/api/v1/waitlist/{entryId}", first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andExpect(jsonPath("$.offeredBookingId").isString())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String expectedBooking = JSON.readTree(offered).get("offeredBookingId").asString();
        assertThat(expectedBooking).isNotBlank();
        assertThat(outbox(session, "waitlist.offered")).isEqualTo(1);

        postAs(session, "/api/v1/bookings/{bookingId}/confirm", null, expectedBooking)
                .andExpect(status().isOk());
        getAs(session, "/api/v1/waitlist/{entryId}", first)
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void anOfferThatLapsesAnnouncesItAndOffersTheNextEntry() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        String holder = fill(session, capacity, "holder");
        String first = entryId(join(session, capacity, "cust-first", 1).andExpect(status().isCreated()));
        String second = entryId(join(session, capacity, "cust-second", 1).andExpect(status().isCreated()));

        postAs(session, "/api/v1/bookings/{bookingId}/cancel", null, holder).andExpect(status().isOk());
        assertThat(inTenant(session.tenant(), offers::execute)).isEqualTo(1);
        String offeredBooking = JSON.readTree(getAs(session, "/api/v1/waitlist/{entryId}", first)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("offeredBookingId")
                .asString();

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.update(BOOKINGS)
                .set(BOOKINGS.HOLD_EXPIRES_AT, Instant.now().minusSeconds(600))
                .where(BOOKINGS.ID.eq(UUID.fromString(offeredBooking)))
                .execute()));
        inTenant(session.tenant(), expirations::execute);

        getAs(session, "/api/v1/waitlist/{entryId}", first)
                .andExpect(jsonPath("$.status").value("LAPSED"));
        assertThat(outbox(session, "waitlist.lapsed")).isEqualTo(1);

        assertThat(inTenant(session.tenant(), offers::execute)).isEqualTo(1);
        getAs(session, "/api/v1/waitlist/{entryId}", second)
                .andExpect(jsonPath("$.status").value("OFFERED"));
    }

    @Test
    void anEntryThatDoesNotFitKeepsItsPositionForTheNextOne() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 2);
        fill(session, capacity, "left");
        String holder = fill(session, capacity, "right");
        String three = entryId(join(session, capacity, "cust-three", 3).andExpect(status().isCreated()));
        String one = entryId(join(session, capacity, "cust-one", 1).andExpect(status().isCreated()));

        postAs(session, "/api/v1/bookings/{bookingId}/cancel", null, holder).andExpect(status().isOk());
        assertThat(inTenant(session.tenant(), offers::execute)).isEqualTo(1);

        getAs(session, "/api/v1/waitlist/{entryId}", three)
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(0));
        getAs(session, "/api/v1/waitlist/{entryId}", one)
                .andExpect(jsonPath("$.status").value("OFFERED"));
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.BOOKING_CONFIRM,
                BookingPermissions.WAITLIST_READ,
                BookingPermissions.WAITLIST_MANAGE);
    }

    private record Capacity(String offeringId, String sessionId) {}

    private Capacity capacity(Session session, int places) throws Exception {
        String code = "wait-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Waitlist","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":30}
                """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(18)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toString();
        String series = postAs(session, "/api/v1/session-series", """
                {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                 "durationMinutes":60,"capacity":%d}
                """.formatted(offering, dtstart, places))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String sessionId = JSON.readTree(series).get("sessions").get(0).get("id").asString();
        return new Capacity(offering, sessionId);
    }

    private String fill(Session session, Capacity capacity, String customer) throws Exception {
        return JSON.readTree(postAs(session, "/api/v1/bookings", """
                        {"offeringId":"%s","customerRef":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), customer, capacity.sessionId()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private org.springframework.test.web.servlet.ResultActions join(
            Session session, Capacity capacity, String customer, int qty) throws Exception {
        return postAs(session, "/api/v1/waitlist", """
                {"offeringId":"%s","customerRef":"%s","qty":%d,"sessionId":"%s","poolCode":"default"}
                """.formatted(capacity.offeringId(), customer, qty, capacity.sessionId()));
    }

    private static String entryId(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return JSON.readTree(actions
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private long outbox(Session session, String type) {
        return inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(OUTBOX, OUTBOX.TYPE.eq(type))));
    }

    private static Instant days(int days) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(days));
    }
}
