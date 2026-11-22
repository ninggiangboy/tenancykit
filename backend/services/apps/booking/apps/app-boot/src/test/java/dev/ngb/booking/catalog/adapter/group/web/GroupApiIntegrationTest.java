package dev.ngb.booking.catalog.adapter.group.web;

import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The resource-group endpoint over the real filter chain, the real database and a real tenant scope: creation with its
 * members, the list and the read, the conditional patch of the strategy and the member set, and the isolation a second
 * tenant sees.
 */
class GroupApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Session catalog() {
        return tenant(BookingPermissions.RESOURCE_READ, BookingPermissions.RESOURCE_MANAGE);
    }

    @Test
    void createGroupAnswers201WithItsMembers() throws Exception {
        Session session = catalog();
        UUID first = seedResource(session, "room-a");
        UUID second = seedResource(session, "room-b");

        postAs(session, "/api/v1/resource-groups", groupBody("front", "Front desk", "PRIORITY", 2, first, 0, second, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("front"))
                .andExpect(jsonPath("$.name").value("Front desk"))
                .andExpect(jsonPath("$.strategy").value("PRIORITY"))
                .andExpect(jsonPath("$.maxCombine").value(2))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].resourceId").value(first.toString()))
                .andExpect(jsonPath("$.members[0].priority").value(0))
                .andExpect(jsonPath("$.members[1].resourceId").value(second.toString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void theListReturnsTheTenantsGroups() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-list");
        createGroup(session, "one", "One", resource);
        createGroup(session, "two", "Two", resource);

        getAs(session, "/api/v1/resource-groups")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getGroupReturnsTheMembers() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-read");
        String id = createGroup(session, "readable", "Readable", resource);

        getAs(session, "/api/v1/resource-groups/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("readable"))
                .andExpect(jsonPath("$.members[0].resourceId").value(resource.toString()));
    }

    @Test
    void aPatchChangesTheStrategyAndTheWholeMemberSet() throws Exception {
        Session session = catalog();
        UUID first = seedResource(session, "room-old");
        UUID second = seedResource(session, "room-new");
        String id = createGroup(session, "patchable", "Patchable", first);

        patchAs(session, "/api/v1/resource-groups/{id}", """
                {"strategy":"ROUND_ROBIN","members":[{"resourceId":"%s","priority":5}]}
                """.formatted(second), "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].resourceId").value(second.toString()))
                .andExpect(jsonPath("$.members[0].priority").value(5));

        getAs(session, "/api/v1/resource-groups/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$.members[0].resourceId").value(second.toString()));
    }

    @Test
    void aPatchWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-conditional");
        String id = createGroup(session, "conditional", "Conditional", resource);

        patchAs(session, "/api/v1/resource-groups/{id}", "{\"strategy\":\"LEAST_BUSY\"}", null, id)
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void aStaleIfMatchIsRefusedWith412() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-stale");
        String id = createGroup(session, "stale", "Stale", resource);

        patchAs(session, "/api/v1/resource-groups/{id}", "{\"strategy\":\"LEAST_BUSY\"}", "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        patchAs(session, "/api/v1/resource-groups/{id}", "{\"strategy\":\"FIT_SEATS\"}", "\"1\"", id)
                .andExpect(status().isPreconditionFailed());

        getAs(session, "/api/v1/resource-groups/{id}", id)
                .andExpect(jsonPath("$.strategy").value("LEAST_BUSY"));
    }

    @Test
    void aDuplicateCodeIsRefusedWith409() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-duplicate");
        createGroup(session, "taken", "Taken", resource);

        postAs(session, "/api/v1/resource-groups", groupBody("taken", "Another", "PRIORITY", 1, resource, 0, null, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
    }

    @Test
    void anUnknownResourceIsRefusedWith422() throws Exception {
        Session session = catalog();

        postAs(session, "/api/v1/resource-groups", groupBody(
                        "unknown", "Unknown", "PRIORITY", 1, UUID.randomUUID(), 0, null, 0))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherTenantsGroupIs404() throws Exception {
        Session one = catalog();
        Session other = catalog();
        UUID resource = seedResource(one, "room-private");
        String id = createGroup(one, "private", "Private", resource);

        getAs(other, "/api/v1/resource-groups/{id}", id).andExpect(status().isNotFound());
        patchAs(other, "/api/v1/resource-groups/{id}", "{\"strategy\":\"LEAST_BUSY\"}", "\"1\"", id)
                .andExpect(status().isNotFound());
    }

    private String createGroup(Session session, String code, String name, UUID resource) throws Exception {
        String response = postAs(
                        session,
                        "/api/v1/resource-groups",
                        groupBody(code, name, "PRIORITY", 1, resource, 0, null, 0))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(response).get("id").asString();
    }

    private static String groupBody(
            String code,
            String name,
            String strategy,
            int maxCombine,
            UUID firstResource,
            int firstPriority,
            UUID secondResource,
            int secondPriority) {
        StringBuilder members = new StringBuilder()
                .append("{\"resourceId\":\"").append(firstResource).append("\",\"priority\":").append(firstPriority).append('}');
        if (secondResource != null) {
            members.append(",{\"resourceId\":\"")
                    .append(secondResource)
                    .append("\",\"priority\":")
                    .append(secondPriority)
                    .append('}');
        }
        return """
                {"code":"%s","name":"%s","strategy":"%s","maxCombine":%d,"members":[%s]}
                """.formatted(code, name, strategy, maxCombine, members);
    }

    /** A resource the tenant holds and has not archived, written as the application role inside its own tenant scope. */
    private UUID seedResource(Session session, String code) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.insertInto(RESOURCES)
                    .set(RESOURCES.TENANT_ID, session.tenant().value())
                    .set(RESOURCES.ID, id)
                    .set(RESOURCES.CODE, code)
                    .set(RESOURCES.NAME, "Resource " + code)
                    .set(RESOURCES.ZONE, "UTC")
                    .set(RESOURCES.STATUS, "ACTIVE")
                    .set(RESOURCES.VERSION, 1L)
                    .set(RESOURCES.CREATED_AT, now)
                    .set(RESOURCES.UPDATED_AT, now)
                    .execute();
        }));
        return id;
    }
}
