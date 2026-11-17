package dev.ngb.booking.inventory.adapter.availability.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The availability endpoint over the real filter chain and the real database: an interval offering's free starts on
 * its slot grid, the resource each requirement takes, the duration rule, and the refusals an unknown or capacity
 * offering gets.
 */
class AvailabilityApiIntegrationTest extends BookingApiTest {

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
    void availabilityListsTheGridWithTheChosenResource() throws Exception {
        Session session = catalog();
        String resource = createResource(session);
        putHours(session, resource);
        String offering = createOffering(session, "INTERVAL", 30, 120, 30, 30);
        putRequirement(session, offering, resource);

        Instant from = tomorrow(3600);
        Instant to = from.plusSeconds(3 * 3600);

        availability(session, offering, from, to, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].start").value(from.toString()))
                .andExpect(jsonPath("$.data[0].end").value(from.plusSeconds(1800).toString()))
                .andExpect(jsonPath("$.data[0].resourceIds.length()").value(1))
                .andExpect(jsonPath("$.data[0].resourceIds[0]").value(resource))
                .andExpect(jsonPath("$.data[1].start").value(from.plusSeconds(1800).toString()));
    }

    @Test
    void aDurationOffTheStepIs422WithInvalidDuration() throws Exception {
        Session session = catalog();
        String resource = createResource(session);
        putHours(session, resource);
        String offering = createOffering(session, "INTERVAL", 30, 120, 30, 30);
        putRequirement(session, offering, resource);

        Instant from = tomorrow(3600);

        availability(session, offering, from, from.plusSeconds(3600), 45)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_DURATION"));
    }

    @Test
    void anUnknownOfferingIs404() throws Exception {
        Session session = catalog();
        Instant from = tomorrow(3600);

        availability(session, UUID.randomUUID().toString(), from, from.plusSeconds(3600), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void aCapacityOfferingIs422WithValidationFailed() throws Exception {
        Session session = catalog();
        String offering = createOffering(session, "CAPACITY", 60, null, 1, 60);

        Instant from = tomorrow(3600);

        availability(session, offering, from, from.plusSeconds(3600), null)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** A whole hour of the next UTC day, so a window and its service ranges stay inside one local day. */
    private static Instant tomorrow(long secondsIntoDay) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1)).plusSeconds(secondsIntoDay);
    }

    private Session catalog() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.OFFERING_READ);
    }

    private ResultActions availability(
            Session session, String offeringId, Instant from, Instant to, Integer durationMinutes) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/v1/offerings/{offeringId}/availability", offeringId)
                        .param("from", from.toString())
                        .param("to", to.toString());
        if (durationMinutes != null) {
            request.param("durationMinutes", durationMinutes.toString());
        }
        return send(request, session, null, null);
    }

    private String createResource(Session session) throws Exception {
        String code = "av-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/resources", """
                {"code":"%s","name":"Availability %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private void putHours(Session session, String resourceId) throws Exception {
        putAs(session, "/api/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resourceId)
                .andExpect(status().isOk());
    }

    private String createOffering(
            Session session, String kind, int durationMin, Integer durationMax, int durationStep, int slotStep)
            throws Exception {
        String code = "av-" + UUID.randomUUID().toString().substring(0, 8);
        String max = durationMax == null ? "null" : durationMax.toString();
        String body = postAs(session, "/api/v1/offerings", """
                {"code":"%s","name":"Availability %s","kind":"%s","durationMin":%d,"durationMax":%s,
                 "durationStep":%d,"slotStep":%d,"horizonDays":30}
                """.formatted(code, code, kind, durationMin, max, durationStep, slotStep))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private void putRequirement(Session session, String offeringId, String resourceId) throws Exception {
        putAs(session, "/api/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resourceId), offeringId)
                .andExpect(status().isOk());
    }
}
