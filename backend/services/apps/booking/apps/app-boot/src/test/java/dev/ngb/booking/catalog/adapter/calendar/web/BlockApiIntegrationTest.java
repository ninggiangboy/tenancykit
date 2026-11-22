package dev.ngb.booking.catalog.adapter.calendar.web;

import static dev.ngb.booking_jooq.Tables.BLOCKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The block endpoints: closing one range on a resource, closing what a rule repeats, reading the closed periods back
 * and reopening one.
 *
 * <p>A block answers the bookings it overlaps, which is empty while no booking exists, and it never deletes one. A
 * body that names a rule without the instants that fix where it starts, or that names neither, is refused with
 * {@code 422} rather than stored as something a later expansion would have to guess at.
 */
class BlockApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aOneOffBlockIsCreatedListedAndRemoved() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-block");

        String body = postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","startsAt":"2026-03-02T09:00:00Z",
                 "endsAt":"2026-03-02T11:00:00Z"}
                """.formatted(resource))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.block.id").isString())
                .andExpect(jsonPath("$.block.resourceId").value(resource))
                .andExpect(jsonPath("$.block.reason").value("Maintenance"))
                .andExpect(jsonPath("$.block.startsAt").value("2026-03-02T09:00:00Z"))
                .andExpect(jsonPath("$.block.endsAt").value("2026-03-02T11:00:00Z"))
                .andExpect(jsonPath("$.block.rule.length()").value(0))
                .andExpect(jsonPath("$.overlappingBookings.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String blockId = JSON.readTree(body).get("block").get("id").asString();

        getAs(session, "/api/v1/blocks")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(blockId))
                .andExpect(jsonPath("$.data[0].reason").value("Maintenance"));

        deleteAs(session, "/api/v1/blocks/{blockId}", null, blockId).andExpect(status().isNoContent());

        assertThat(rowsOf(session, BLOCKS)).isZero();
        getAs(session, "/api/v1/blocks").andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aRecurringBlockStoresTheRuleAndItsFirstOccurrence() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-recurring");

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Weekly cleaning","startsAt":"2026-03-02T09:00:00Z",
                 "endsAt":"2026-03-02T10:00:00Z","rule":["RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4"]}
                """.formatted(resource))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.block.rule.length()").value(1))
                .andExpect(jsonPath("$.block.rule[0]").value("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4"))
                .andExpect(jsonPath("$.block.startsAt").value("2026-03-02T09:00:00Z"));

        assertThat(rowsOf(session, BLOCKS)).isEqualTo(1);
    }

    @Test
    void anUnknownResourceIs404() throws Exception {
        Session session = calendar();

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","startsAt":"2026-03-02T09:00:00Z",
                 "endsAt":"2026-03-02T11:00:00Z"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());

        assertThat(rowsOf(session, BLOCKS)).isZero();
    }

    @Test
    void aRuleWithoutItsInstantsIsRefusedWith422() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-rule");

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","rule":["RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4"]}
                """.formatted(resource))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowsOf(session, BLOCKS)).isZero();
    }

    @Test
    void neitherARangeNorARuleIsRefusedWith422() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-empty");

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance"}
                """.formatted(resource))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowsOf(session, BLOCKS)).isZero();
    }

    @Test
    void aRuleTheExpanderCannotReadIsRefusedWith422() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-unreadable");

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","startsAt":"2026-03-02T09:00:00Z",
                 "endsAt":"2026-03-02T10:00:00Z","rule":["RRULE:FREQ=NEVER"]}
                """.formatted(resource))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("invalid-recurrence"));

        assertThat(rowsOf(session, BLOCKS)).isZero();
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefusedWith422() throws Exception {
        Session session = calendar();
        String resource = createResource(session, "room-reversed");

        postAs(session, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","startsAt":"2026-03-02T11:00:00Z",
                 "endsAt":"2026-03-02T09:00:00Z"}
                """.formatted(resource))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherTenantsBlockIs404OnDelete() throws Exception {
        Session one = calendar();
        Session other = calendar();
        String resource = createResource(one, "room-private");
        String body = postAs(one, "/api/v1/blocks", """
                {"resourceId":"%s","reason":"Maintenance","startsAt":"2026-03-02T09:00:00Z",
                 "endsAt":"2026-03-02T11:00:00Z"}
                """.formatted(resource))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String blockId = JSON.readTree(body).get("block").get("id").asString();

        deleteAs(other, "/api/v1/blocks/{blockId}", null, blockId).andExpect(status().isNotFound());
        getAs(other, "/api/v1/blocks").andExpect(jsonPath("$.data.length()").value(0));
    }

    private Session calendar() {
        return tenant(
                BookingPermissions.RESOURCE_READ,
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.CALENDAR_READ,
                BookingPermissions.CALENDAR_MANAGE);
    }

    private String createResource(Session session, String code) throws Exception {
        return JSON.readTree(postAs(session, "/api/v1/resources", """
                        {"code":"%s","name":"Resource %s","zone":"UTC"}
                        """.formatted(code, code))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }
}
