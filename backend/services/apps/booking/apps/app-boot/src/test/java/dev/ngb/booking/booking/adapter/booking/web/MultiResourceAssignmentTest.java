package dev.ngb.booking.booking.adapter.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Filling every requirement of an offering from resources and groups: staff plus room all or nothing, the four group
 * strategies picking as their definitions say, and a seat-limited group combining tables for a party.
 */
class MultiResourceAssignmentTest extends BookingApiTest {

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
    void staffAndRoomAreClaimedTogetherOrNotAtAll() throws Exception {
        Session session = lifecycle();
        UUID staff = resource(session, null);
        UUID room = resource(session, null);
        String staffOnly = offering(session, requirement("staff", staff));
        hold(session, staffOnly, "taken", tomorrow(10)).andExpect(status().isCreated());

        String both = offering(session, requirement("staff", staff) + "," + requirement("room", room));
        hold(session, both, "atomic", tomorrow(10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("slot-taken"));

        String roomOnly = offering(session, requirement("room", room));
        hold(session, roomOnly, "room-free", tomorrow(10)).andExpect(status().isCreated());
    }

    @Test
    void priorityAlwaysTakesTheFirstMember() throws Exception {
        Session session = lifecycle();
        UUID first = resource(session, null);
        UUID second = resource(session, null);
        String group = group(session, "PRIORITY", 1, member(first, 0), member(second, 10));
        String offering = offering(session, groupRequirement("staff", group));

        assertThat(resourcesOf(hold(session, offering, "priority-1", tomorrow(9)).andExpect(status().isCreated())))
                .containsExactly(first);
        assertThat(resourcesOf(hold(session, offering, "priority-2", tomorrow(10)).andExpect(status().isCreated())))
                .containsExactly(first);
    }

    @Test
    void roundRobinMovesPastTheLastMember() throws Exception {
        Session session = lifecycle();
        UUID first = resource(session, null);
        UUID second = resource(session, null);
        String group = group(session, "ROUND_ROBIN", 1, member(first, 0), member(second, 10));
        String offering = offering(session, groupRequirement("staff", group));

        assertThat(resourcesOf(hold(session, offering, "rr-1", tomorrow(9)).andExpect(status().isCreated())))
                .containsExactly(first);
        assertThat(resourcesOf(hold(session, offering, "rr-2", tomorrow(10)).andExpect(status().isCreated())))
                .containsExactly(second);
        assertThat(resourcesOf(hold(session, offering, "rr-3", tomorrow(11)).andExpect(status().isCreated())))
                .containsExactly(first);
    }

    @Test
    void leastBusyLeavesTheDayToTheMemberWithNoWork() throws Exception {
        Session session = lifecycle();
        UUID first = resource(session, null);
        UUID second = resource(session, null);
        String group = group(session, "LEAST_BUSY", 1, member(first, 0), member(second, 10));
        String offering = offering(session, groupRequirement("staff", group));

        assertThat(resourcesOf(hold(session, offering, "busy-1", tomorrow(9)).andExpect(status().isCreated())))
                .containsExactly(first);
        assertThat(resourcesOf(hold(session, offering, "busy-2", tomorrow(10)).andExpect(status().isCreated())))
                .containsExactly(second);
    }

    @Test
    void fitSeatsTakesTheSmallestSufficientTable() throws Exception {
        Session session = lifecycle();
        UUID four = resource(session, 4);
        UUID eight = resource(session, 8);
        String group = group(session, "FIT_SEATS", 1, member(four, 0), member(eight, 10));
        String offering = offering(session, groupRequirement("table", group));

        ResultActions held = hold(session, offering, "party-3", tomorrow(9), 3).andExpect(status().isCreated());
        assertThat(resourcesOf(held)).containsExactly(four);
    }

    @Test
    void fitSeatsCombinesTablesWhenNoSingleOneIsEnough() throws Exception {
        Session session = lifecycle();
        UUID left = resource(session, 2);
        UUID right = resource(session, 2);
        String group = group(session, "FIT_SEATS", 2, member(left, 0), member(right, 10));
        String offering = offering(session, groupRequirement("table", group));

        ResultActions held = hold(session, offering, "party-4", tomorrow(9), 4).andExpect(status().isCreated());
        assertThat(resourcesOf(held)).containsExactlyInAnyOrder(left, right);

        List<UUID> availability = availability(session, offering, tomorrow(11), 4);
        assertThat(availability)
                .as("one start, two tables, and both are one requirement")
                .containsExactlyInAnyOrder(left, right);
    }

    @Test
    void fitSeatsRefusesACombinationPastTheCap() throws Exception {
        Session session = lifecycle();
        UUID left = resource(session, 2);
        UUID right = resource(session, 2);
        String group = group(session, "FIT_SEATS", 1, member(left, 0), member(right, 10));
        String offering = offering(session, groupRequirement("table", group));

        hold(session, offering, "party-4", tomorrow(9), 4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("slot-taken"));
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.OFFERING_READ,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD);
    }

    private UUID resource(Session session, Integer seats) throws Exception {
        String code = "res-" + UUID.randomUUID().toString().substring(0, 8);
        String seatField = seats == null ? "" : ",\"seats\":" + seats;
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Resource %s","zone":"UTC"%s}
                """.formatted(code, code, seatField))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }

    private String group(Session session, String strategy, int maxCombine, String... members) throws Exception {
        String code = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resource-groups", """
                {"code":"%s","name":"Group %s","strategy":"%s","maxCombine":%d,"members":[%s]}
                """.formatted(code, code, strategy, maxCombine, String.join(",", members)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    private static String member(UUID resourceId, int priority) {
        return """
                {"resourceId":"%s","priority":%d}
                """.formatted(resourceId, priority);
    }

    private static String requirement(String slotCode, UUID resourceId) {
        return """
                {"slotCode":"%s","resourceId":"%s"}
                """.formatted(slotCode, resourceId);
    }

    private static String groupRequirement(String slotCode, String groupId) {
        return """
                {"slotCode":"%s","groupId":"%s"}
                """.formatted(slotCode, groupId);
    }

    private String offering(Session session, String requirements) throws Exception {
        String code = "off-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/offerings", """
                {"code":"%s","name":"Offering %s","kind":"INTERVAL","durationMin":60,"slotStep":30,"horizonDays":30}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String offering = JSON.readTree(body).get("id").asString();
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[%s]}
                """.formatted(requirements), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private ResultActions hold(Session session, String offeringId, String customerRef, Instant start)
            throws Exception {
        return hold(session, offeringId, customerRef, start, null);
    }

    private ResultActions hold(Session session, String offeringId, String customerRef, Instant start, Integer partySize)
            throws Exception {
        String party = partySize == null ? "" : ",\"partySize\":" + partySize;
        return postAs(session, "/system/v1/bookings", """
                {"offeringId":"%s","customerRef":"%s","start":"%s","durationMinutes":60%s}
                """.formatted(offeringId, customerRef, start, party));
    }

    private List<UUID> resourcesOf(ResultActions actions) throws Exception {
        String body = actions
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode resources = JSON.readTree(body).get("resources");
        List<UUID> ids = new ArrayList<>(resources.size());
        for (JsonNode resource : resources) {
            ids.add(UUID.fromString(resource.get("resourceId").asString()));
        }
        return ids;
    }

    private List<UUID> availability(Session session, String offeringId, Instant start, Integer partySize)
            throws Exception {
        String body = getAs(session, "/system/v1/offerings/{offeringId}/availability?from=%s&to=%s&partySize=%d"
                        .formatted(start, start.plus(Duration.ofHours(1)), partySize), offeringId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode first = JSON.readTree(body).get("data").get(0);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode id : first.get("resourceIds")) {
            ids.add(UUID.fromString(id.asString()));
        }
        return ids;
    }

    private static Instant tomorrow(int hour) {
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(hour));
    }
}
