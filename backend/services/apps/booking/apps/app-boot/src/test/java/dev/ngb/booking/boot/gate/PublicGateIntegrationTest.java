package dev.ngb.booking.boot.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public surface with a real customer token: reading an offering, holding and releasing its own hold, and using the
 * waitlist. Refusals are the point of many cases here — a missing, expired, wrong-scope, wrong-offering or foreign-tenant
 * token must not work, and there is no confirm route at all.
 */
class PublicGateIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aCustomerTokenReadsHoldsReleasesAndWaits() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        String token = mint(session, "cust-public", List.of("browse", "hold", "waitlist"), null, 600);

        mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(capacity.offeringId()))
                .andExpect(jsonPath("$.kind").value("CAPACITY"));

        String held = JSON.readTree(mvc.perform(publicPost("/p/bookings", session, token, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("GATE"))
                .andExpect(jsonPath("$.customerRef").value("cust-public"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        mvc.perform(publicDelete("/p/bookings/" + held, session, token))
                .andExpect(status().isNoContent());

        fill(session, capacity, "holder");
        String entry = JSON.readTree(mvc.perform(publicPost("/p/waitlist", session, token, """
                        {"offeringId":"%s","qty":1,"sessionId":"%s","poolCode":"default"}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        mvc.perform(publicGet("/p/waitlist/" + entry, session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerRef").value("cust-public"));
        mvc.perform(publicDelete("/p/waitlist/" + entry, session, token))
                .andExpect(status().isNoContent());
    }

    @Test
    void aMissingExpiredOrForeignTokenIsRefusedAndConfirmDoesNotExist() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 2);

        mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, null))
                .andExpect(status().isUnauthorized());
        mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, "not-a-token"))
                .andExpect(status().isUnauthorized());

        String browseOnly = mint(session, "cust-browse", List.of("browse"), null, 600);
        mvc.perform(publicPost("/p/bookings", session, browseOnly, """
                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_FORBIDDEN"));

        String bound = mint(session, "cust-bound", List.of("browse"), UUID.fromString(capacity.offeringId()), 600);
        Session other = lifecycle();
        Capacity otherCapacity = capacity(other, 1);
        mvc.perform(publicGet("/p/offerings/" + otherCapacity.offeringId(), session, bound))
                .andExpect(status().isForbidden());

        String foreign = mint(other, "cust-foreign", List.of("browse"), null, 600);
        mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, foreign))
                .andExpect(status().isUnauthorized());

        String expired = mint(session, "cust-expired", List.of("browse"), null, 1);
        Thread.sleep(2100);
        mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, expired))
                .andExpect(status().isUnauthorized());

        String hold = mint(session, "cust-hold", List.of("hold"), null, 600);
        mvc.perform(publicPost("/p/bookings/" + UUID.randomUUID() + "/confirm", session, hold, "{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherCustomersWaitlistEntryIsNotFound() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        fill(session, capacity, "holder");
        String owner = mint(session, "cust-owner", List.of("waitlist"), null, 600);
        String entry = JSON.readTree(mvc.perform(publicPost("/p/waitlist", session, owner, """
                        {"offeringId":"%s","qty":1,"sessionId":"%s","poolCode":"default"}
                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String stranger = mint(session, "cust-stranger", List.of("waitlist"), null, 600);
        mvc.perform(publicGet("/p/waitlist/" + entry, session, stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRateBucketAnswers429WithRetryAfter() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        String token = mint(session, "cust-rate", List.of("browse"), null, 600);
        int limited = 0;
        for (int i = 0; i < 125; i++) {
            int status = mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, token))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status == 429) {
                limited++;
            }
        }
        assertThat(limited).as("the request after the minute's budget is refused").isPositive();
        var response = mvc.perform(publicGet("/p/offerings/" + capacity.offeringId(), session, token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn()
                .getResponse();
        assertThat(response.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void corsEchoesOnlyARegisteredOrigin() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 1);
        // booking:settings:manage is not grantable, so it is edited by a person, not by the tenant's system.
        String admin = personOf(session, BookingPermissions.SETTINGS_MANAGE);
        putAsToken(session, "/api/v1/settings/gate", """
                {"allowedOrigins":["https://shop.example.com"],"maxTokenTtlSeconds":600}
                """, admin).andExpect(status().isOk());

        mvc.perform(options("/p/offerings/" + capacity.offeringId())
                        .header("Host", session.host())
                        .header("Origin", "https://shop.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.example.com"));

        mvc.perform(options("/p/offerings/" + capacity.offeringId())
                        .header("Host", session.host())
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void fortyParallelGateHoldsTakeExactlyTwentyPlaces() throws Exception {
        Session session = lifecycle();
        Capacity capacity = capacity(session, 20);
        String token = mint(session, "cust-rush", List.of("hold"), null, 600);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                attempts.add(() -> {
                    for (int retry = 0; retry < 40; retry++) {
                        int status = mvc.perform(publicPost("/p/bookings", session, token, """
                                        {"offeringId":"%s","sessionId":"%s","poolCode":"default","qty":1}
                                        """.formatted(capacity.offeringId(), capacity.sessionId())))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                        if (status != 429) {
                            return status;
                        }
                        Thread.sleep(50);
                    }
                    return 429;
                });
            }
            int holds = 0;
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                if (result.get() == 201) {
                    holds++;
                }
            }
            assertThat(holds).as("the pool's places bound the holds").isEqualTo(20);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.TOKEN_ISSUE,
                BookingPermissions.SETTINGS_MANAGE);
    }

    private record Capacity(String offeringId, String sessionId) {}

    private Capacity capacity(Session session, int places) throws Exception {
        String code = "gate-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Gate","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":30}
                        """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(18)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toString();
        String sessionId = JSON.readTree(postAs(session, "/system/v1/session-series", """
                        {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                         "durationMinutes":60,"capacity":%d}
                        """.formatted(offering, dtstart, places))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("sessions")
                .get(0)
                .get("id")
                .asString();
        return new Capacity(offering, sessionId);
    }

    private void fill(Session session, Capacity capacity, String customer) throws Exception {
        postAs(session, "/system/v1/bookings", """
                        {"offeringId":"%s","customerRef":"%s","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(capacity.offeringId(), customer, capacity.sessionId()))
                .andExpect(status().isCreated());
    }

    private String mint(Session session, String customerRef, List<String> scopes, UUID offeringId, int ttl)
            throws Exception {
        String offering = offeringId == null ? "" : ",\"offeringId\":\"" + offeringId + "\"";
        String body = JSON.readTree(postAs(session, "/system/v1/customer-tokens", """
                        {"customerRef":"%s","scopes":[%s],"ttlSeconds":%d%s}
                        """.formatted(
                                customerRef,
                                scopes.stream().map(scope -> "\"" + scope + "\"").reduce((a, b) -> a + "," + b).orElse(""),
                                ttl,
                                offering))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("token")
                .asString();
        return body;
    }

    private MockHttpServletRequestBuilder publicGet(String path, Session session, String token) {
        return withToken(get(path), session, token);
    }

    private MockHttpServletRequestBuilder publicPost(String path, Session session, String token, String body) {
        return withToken(post(path), session, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder publicDelete(String path, Session session, String token) {
        return withToken(delete(path), session, token);
    }

    private static MockHttpServletRequestBuilder withToken(
            MockHttpServletRequestBuilder request, Session session, String token) {
        request.header("Host", session.host());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }
}
