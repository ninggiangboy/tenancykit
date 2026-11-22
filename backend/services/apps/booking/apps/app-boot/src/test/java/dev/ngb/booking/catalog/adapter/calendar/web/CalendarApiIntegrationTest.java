package dev.ngb.booking.catalog.adapter.calendar.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The calendar endpoints: replacing and reading a resource's weekly hours, and adding, listing and removing the dated
 * exceptions to them.
 *
 * <p>The hours cross the wire as {@code HH:mm}, with {@code 24:00} for a close at the end of a day, and a window that
 * overlaps its neighbour or closes before it opens is refused with {@code 422}.
 */
class CalendarApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theWeeklyHoursAreReplacedAndReadBack() throws Exception {
        Session session = reader();
        String id = create(session);

        putAs(session, "/api/v1/resources/{resourceId}/working-hours", """
                {"hours":[{"weekday":1,"opens":"09:00","closes":"17:00"},{"weekday":2,"opens":"08:00","closes":"12:00"}]}
                """, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.length()").value(2))
                .andExpect(jsonPath("$.hours[0].weekday").value(1))
                .andExpect(jsonPath("$.hours[0].opens").value("09:00"))
                .andExpect(jsonPath("$.hours[0].closes").value("17:00"));

        getAs(session, "/api/v1/resources/{resourceId}/working-hours", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.length()").value(2))
                .andExpect(jsonPath("$.hours[1].weekday").value(2))
                .andExpect(jsonPath("$.hours[1].opens").value("08:00"))
                .andExpect(jsonPath("$.hours[1].closes").value("12:00"));
    }

    @Test
    void aWindowMayCloseAtTheEndOfADay() throws Exception {
        Session session = reader();
        String id = create(session);

        putAs(session, "/api/v1/resources/{resourceId}/working-hours", """
                {"hours":[{"weekday":7,"opens":"09:00","closes":"24:00"}]}
                """, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours[0].closes").value("24:00"));

        getAs(session, "/api/v1/resources/{resourceId}/working-hours", id)
                .andExpect(jsonPath("$.hours[0].closes").value("24:00"));
    }

    @Test
    void overlappingWindowsAreRefusedWith422() throws Exception {
        Session session = reader();
        String id = create(session);

        putAs(session, "/api/v1/resources/{resourceId}/working-hours", """
                {"hours":[{"weekday":1,"opens":"09:00","closes":"12:00"},{"weekday":1,"opens":"11:00","closes":"13:00"}]}
                """, id)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        getAs(session, "/api/v1/resources/{resourceId}/working-hours", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.length()").value(0));
    }

    @Test
    void aWindowThatClosesBeforeItOpensIsRefusedWith422() throws Exception {
        Session session = reader();
        String id = create(session);

        putAs(session, "/api/v1/resources/{resourceId}/working-hours", """
                {"hours":[{"weekday":1,"opens":"17:00","closes":"09:00"}]}
                """, id)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aClosedExceptionStoresNoHoursAndAnOpenOneKeepsItsOwn() throws Exception {
        Session session = reader();
        String id = create(session);

        postAs(session, "/api/v1/resources/{resourceId}/exceptions", """
                {"fromDate":"2026-12-25","toDate":"2026-12-26","kind":"CLOSED"}
                """, id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.kind").value("CLOSED"))
                .andExpect(jsonPath("$.hours.length()").value(0));

        postAs(session, "/api/v1/resources/{resourceId}/exceptions", """
                {"fromDate":"2026-12-24","toDate":"2026-12-24","kind":"OPEN",
                 "hours":[{"weekday":4,"opens":"10:00","closes":"14:00"}]}
                """, id)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("OPEN"))
                .andExpect(jsonPath("$.hours.length()").value(1))
                .andExpect(jsonPath("$.hours[0].opens").value("10:00"))
                .andExpect(jsonPath("$.hours[0].closes").value("14:00"));

        getAs(session, "/api/v1/resources/{resourceId}/exceptions", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].fromDate").value("2026-12-24"))
                .andExpect(jsonPath("$.data[0].kind").value("OPEN"))
                .andExpect(jsonPath("$.data[1].kind").value("CLOSED"));
    }

    @Test
    void anOpenExceptionWithoutHoursIsRefusedWith422() throws Exception {
        Session session = reader();
        String id = create(session);

        postAs(session, "/api/v1/resources/{resourceId}/exceptions", """
                {"fromDate":"2026-12-24","toDate":"2026-12-24","kind":"OPEN"}
                """, id)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anExceptionIsRemovedAndASecondRemovalIs404() throws Exception {
        Session session = reader();
        String id = create(session);
        String body = postAs(session, "/api/v1/resources/{resourceId}/exceptions", """
                {"fromDate":"2026-12-25","toDate":"2026-12-25","kind":"CLOSED"}
                """, id)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String exceptionId = JSON.readTree(body).get("id").asString();

        deleteAs(session, "/api/v1/resources/{resourceId}/exceptions/{exceptionId}", null, id, exceptionId)
                .andExpect(status().isNoContent());
        deleteAs(session, "/api/v1/resources/{resourceId}/exceptions/{exceptionId}", null, id, exceptionId)
                .andExpect(status().isNotFound());

        getAs(session, "/api/v1/resources/{resourceId}/exceptions", id)
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void anUnknownResourceIs404() throws Exception {
        Session session = reader();
        String unknown = UUID.randomUUID().toString();

        getAs(session, "/api/v1/resources/{resourceId}/working-hours", unknown).andExpect(status().isNotFound());
        getAs(session, "/api/v1/resources/{resourceId}/exceptions", unknown).andExpect(status().isNotFound());
        putAs(session, "/api/v1/resources/{resourceId}/working-hours", "{\"hours\":[]}", unknown)
                .andExpect(status().isNotFound());
    }

    private Session reader() {
        return tenant(BookingPermissions.RESOURCE_READ, BookingPermissions.RESOURCE_MANAGE);
    }

    private String create(Session session) throws Exception {
        String code = "cal-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/api/v1/resources", """
                {"code":"%s","name":"Calendar %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }
}
