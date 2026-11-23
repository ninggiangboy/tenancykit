package dev.ngb.booking.catalog.adapter.resource.web;

import static dev.ngb.booking_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static dev.ngb.booking_jooq.Tables.RESOURCE_GROUPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The resource endpoint: create, read, patch, archive and list, over the real filter chain, the real database and the
 * list-query contract.
 *
 * <p>The cases that decide the design are here: a patch without its {@code If-Match} is refused rather than applied
 * last-write-wins, a stale tag is refused with the current one, and a resource nothing references is deleted while a
 * referenced one is archived.
 */
class ResourceApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Session reader() {
        return tenant(BookingPermissions.RESOURCE_READ, BookingPermissions.RESOURCE_MANAGE);
    }

    @Test
    void createAnswers201AndTheVersionAsTheETag() throws Exception {
        Session session = reader();

        postAs(session, "/system/v1/resources", """
                {"code":"room-1","name":"Room One","zone":"Europe/London","seats":4}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("room-1"))
                .andExpect(jsonPath("$.name").value("Room One"))
                .andExpect(jsonPath("$.zone").value("Europe/London"))
                .andExpect(jsonPath("$.seats").value(4))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(rowsOf(session, RESOURCES)).isEqualTo(1);
    }

    @Test
    void aSecondResourceWithTheSameCodeIsRefusedWith409() throws Exception {
        Session session = reader();
        String body = """
                {"code":"room-dup","name":"Room Dup","zone":"UTC"}
                """;

        postAs(session, "/system/v1/resources", body).andExpect(status().isCreated());
        postAs(session, "/system/v1/resources", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

        assertThat(rowsOf(session, RESOURCES)).isEqualTo(1);
    }

    @Test
    void aResourceWithAnUnknownZoneIsRefusedWith422() throws Exception {
        Session session = reader();

        postAs(session, "/system/v1/resources", """
                {"code":"room-mars","name":"Room Mars","zone":"Mars/Olympus"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowsOf(session, RESOURCES)).isZero();
    }

    @Test
    void aReadCarriesTheVersionAsTheETag() throws Exception {
        Session session = reader();
        String id = create(session, "room-read");

        getAs(session, "/system/v1/resources/{resourceId}", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.code").value("room-read"));
    }

    @Test
    void aPatchChangesOnlyThePropertiesItCarries() throws Exception {
        Session session = reader();
        String id = create(session, "room-patch");

        patchAs(session, "/system/v1/resources/{resourceId}", "{\"name\":\"Renamed\"}", "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.zone").value("UTC"))
                .andExpect(jsonPath("$.seats").value(2));

        getAs(session, "/system/v1/resources/{resourceId}", id)
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void aPatchWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = reader();
        String id = create(session, "room-conditional");

        patchAs(session, "/system/v1/resources/{resourceId}", "{\"name\":\"Renamed\"}", null, id)
                .andExpect(status().isPreconditionRequired());

        getAs(session, "/system/v1/resources/{resourceId}", id).andExpect(jsonPath("$.name").value("Resource room-conditional"));
    }

    @Test
    void aStaleIfMatchIsRefusedWith412AndCarriesTheCurrentETag() throws Exception {
        Session session = reader();
        String id = create(session, "room-stale");

        patchAs(session, "/system/v1/resources/{resourceId}", "{\"name\":\"First\"}", "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        patchAs(session, "/system/v1/resources/{resourceId}", "{\"name\":\"Second\"}", "\"1\"", id)
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string("ETag", "\"2\""));

        getAs(session, "/system/v1/resources/{resourceId}", id).andExpect(jsonPath("$.name").value("First"));
    }

    @Test
    void anUnreferencedResourceIsDeletedWith204() throws Exception {
        Session session = reader();
        String id = create(session, "room-delete");

        deleteAs(session, "/system/v1/resources/{resourceId}", "\"1\"", id).andExpect(status().isNoContent());

        assertThat(rowsOf(session, RESOURCES)).isZero();
        getAs(session, "/system/v1/resources/{resourceId}", id).andExpect(status().isNotFound());
    }

    @Test
    void aReferencedResourceIsArchivedWith204AndKept() throws Exception {
        Session session = reader();
        String id = create(session, "room-referenced");
        reference(session, id);

        deleteAs(session, "/system/v1/resources/{resourceId}", "\"1\"", id).andExpect(status().isNoContent());

        assertThat(rowsOf(session, RESOURCES)).isEqualTo(1);
        getAs(session, "/system/v1/resources/{resourceId}", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void archiveWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = reader();
        String id = create(session, "room-guarded");

        deleteAs(session, "/system/v1/resources/{resourceId}", null, id).andExpect(status().isPreconditionRequired());

        assertThat(rowsOf(session, RESOURCES)).isEqualTo(1);
    }

    @Test
    void theListFiltersSortsAndPagesByTheContract() throws Exception {
        Session session = reader();
        create(session, "r-a");
        create(session, "r-b");
        create(session, "r-c");

        String firstPage = list(session, "filter[status][eq]", "ACTIVE", "sort", "code", "limit", "2", "page_info", "cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value("r-a"))
                .andExpect(jsonPath("$.data[1].code").value("r-b"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cursor = JSON.readTree(firstPage).get("page").get("next_cursor").asString();
        assertThat(cursor).as("the first page is not the last one").isNotBlank();

        list(session,
                        "filter[status][eq]", "ACTIVE",
                        "sort", "code",
                        "limit", "2",
                        "page_info", "cursor",
                        "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("r-c"))
                .andExpect(jsonPath("$.page.next_cursor").doesNotExist());
    }

    @Test
    void anUnknownResourceIs404() throws Exception {
        Session session = reader();

        getAs(session, "/system/v1/resources/{resourceId}", UUID.randomUUID()).andExpect(status().isNotFound());
    }

    /** Creates a resource as the session and answers its id. */
    private String create(Session session, String code) throws Exception {
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Resource %s","zone":"UTC","seats":2}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }

    /** Makes a group name the resource, so a delete has to archive instead of deleting. */
    private void reference(Session session, String resourceId) {
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            UUID groupId = UUID.randomUUID();
            dsl.insertInto(RESOURCE_GROUPS)
                    .set(RESOURCE_GROUPS.TENANT_ID, session.tenant().value())
                    .set(RESOURCE_GROUPS.ID, groupId)
                    .set(RESOURCE_GROUPS.CODE, "group-" + groupId)
                    .set(RESOURCE_GROUPS.NAME, "Group")
                    .set(RESOURCE_GROUPS.STRATEGY, "PRIORITY")
                    .set(RESOURCE_GROUPS.CREATED_AT, Instant.now())
                    .set(RESOURCE_GROUPS.UPDATED_AT, Instant.now())
                    .execute();
            dsl.insertInto(GROUP_MEMBERS)
                    .set(GROUP_MEMBERS.TENANT_ID, session.tenant().value())
                    .set(GROUP_MEMBERS.GROUP_ID, groupId)
                    .set(GROUP_MEMBERS.RESOURCE_ID, UUID.fromString(resourceId))
                    .set(GROUP_MEMBERS.PRIORITY, 0)
                    .execute();
        }));
    }

    private ResultActions list(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/system/v1/resources");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return send(request, session, null, null);
    }
}
