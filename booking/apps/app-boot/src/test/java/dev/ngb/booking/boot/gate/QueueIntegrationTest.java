package dev.ngb.booking.boot.gate;

import static dev.ngb.booking_jooq.Tables.QUEUE_TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The waiting room: tickets are admitted in issue order, a poll reads the watermark rather than the ticket, and one
 * admitted ticket funds exactly one hold.
 */
class QueueIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    QueueTicketRepository tickets;

    @Test
    void aTicketIsAdmittedInOrderAndFundsExactlyOneHold() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 20, 100, 120);
        String token = mint(session, "cust-queue", List.of("hold"));
        String[] others = {
            mint(session, "cust-two", List.of("hold")), mint(session, "cust-three", List.of("hold"))
        };

        String first = enter(session, token, capacity);
        enter(session, others[0], capacity);
        enter(session, others[1], capacity);

        mvc.perform(publicGet("/p/queue/status", session, token).header("X-Queue-Ticket", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1));

        long target = inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> tickets.admit("session:" + capacity.sessionId(), 3, Instant.now())));
        assertThat(target).isEqualTo(3);
        List<String> order = inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> dsl.select(QUEUE_TICKETS.TICKET_NO, QUEUE_TICKETS.STATUS)
                        .from(QUEUE_TICKETS)
                        .orderBy(QUEUE_TICKETS.TICKET_NO)
                        .fetch()
                        .map(row -> row.value2())));
        assertThat(order).containsExactly("ADMITTED", "ADMITTED", "ADMITTED");

        Thread.sleep(1100);
        mvc.perform(publicGet("/p/queue/status", session, token).header("X-Queue-Ticket", first))
                .andExpect(jsonPath("$.status").value("ADMITTED"));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                attempts.add(() -> mvc.perform(publicPost("/p/bookings", session, token, """
                                {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                                """.formatted(capacity.offeringId(), capacity.sessionId()))
                        .header("X-Queue-Ticket", first))
                        .andReturn()
                        .getResponse()
                        .getStatus());
            }
            int holds = 0;
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                if (result.get() == 201) {
                    holds++;
                }
            }
            assertThat(holds).as("one ticket funds exactly one hold").isEqualTo(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void withoutATicketAWaiterOrAnExpiredAdmissionCannotHold() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 5, 100, 1);
        String token = mint(session, "cust-guard", List.of("hold"));

        mvc.perform(publicPost("/p/bookings", session, token, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("queue-required"));

        String ticket = enter(session, token, capacity);
        mvc.perform(publicPost("/p/bookings", session, token, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId()))
                .header("X-Queue-Ticket", ticket))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not-admitted"));

        inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> tickets.admit("session:" + capacity.sessionId(), 1, Instant.now())));
        Thread.sleep(2200);
        mvc.perform(publicPost("/p/bookings", session, token, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId()))
                .header("X-Queue-Ticket", ticket))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not-admitted"));
    }

    @Test
    void anotherCustomersTicketDoesNotAdmitThisCustomer() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 5, 100, 120);
        String owner = mint(session, "cust-owner-ticket", List.of("hold"));
        String other = mint(session, "cust-other-ticket", List.of("hold"));
        String ticket = enter(session, owner, capacity);
        inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> tickets.admit("session:" + capacity.sessionId(), 1, Instant.now())));

        mvc.perform(publicPost("/p/bookings", session, other, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId()))
                .header("X-Queue-Ticket", ticket))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not-admitted"));
    }

    @Test
    void leavingRemovesTheTicketFromTheRoom() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 5, 100, 120);
        String token = mint(session, "cust-leave-queue", List.of("hold"));
        String ticket = enter(session, token, capacity);

        mvc.perform(delete("/p/queue")
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Queue-Ticket", ticket))
                .andExpect(status().isNoContent());
        assertThat(inTenant(
                        session.tenant(),
                        () -> unitOfWork.transactional(() -> dsl.fetchCount(
                                QUEUE_TICKETS, QUEUE_TICKETS.STATUS.eq("LEFT")))))
                .isEqualTo(1);
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.TOKEN_ISSUE);
    }

    private record Capacity(String offeringId, String sessionId) {}

    private Capacity capacity(Session session, int places, int perSecond, int window) throws Exception {
        String code = "queue-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/api/v1/offerings", """
                        {"code":"%s","name":"Queue","kind":"CAPACITY","durationMin":60,"slotStep":60,
                         "horizonDays":30,"queueMode":"REQUIRED","admitPerSecond":%d,"admitWindowSeconds":%d}
                        """.formatted(code, perSecond, window))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(18).withMinute(0).withSecond(0).withNano(0)
                .toString();
        String sessionId = JSON.readTree(postAs(session, "/api/v1/session-series", """
                        {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                         "durationMinutes":60,"capacity":%d}
                        """.formatted(offering, dtstart, places))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("sessions").get(0).get("id").asString();
        return new Capacity(offering, sessionId);
    }

    private String mint(Session session, String customerRef, List<String> scopes) throws Exception {
        return JSON.readTree(postAs(session, "/api/v1/customer-tokens", """
                        {"customerRef":"%s","scopes":["%s"],"ttlSeconds":600}
                        """.formatted(customerRef, String.join("\",\"", scopes)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("token")
                .asString();
    }

    private String enter(Session session, String token, Capacity capacity) throws Exception {
        return JSON.readTree(mvc.perform(publicPost("/p/queue", session, token, """
                        {"offeringId":"%s","sessionId":"%s"}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("ticketToken")
                .asString();
    }

    private MockHttpServletRequestBuilder publicPost(String path, Session session, String token, String body) {
        return post(path)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder publicGet(String path, Session session, String token) {
        return get(path).header("Host", session.host()).header("Authorization", "Bearer " + token);
    }
}
