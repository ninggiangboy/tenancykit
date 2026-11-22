package dev.ngb.booking.catalog.adapter.offering.web;

import static dev.ngb.booking_jooq.Tables.RESOURCES;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offering endpoint over the real filter chain, the real database and a real tenant scope: creation and its tag,
 * the conditional patch, the list contract, archiving, the requirement and cancellation-window replacement, and the
 * isolation a second tenant sees.
 */
class OfferingApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Session catalog() {
        return tenant(
                BookingPermissions.OFFERING_READ,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.RESOURCE_READ,
                BookingPermissions.RESOURCE_MANAGE);
    }

    @Test
    void createOfferingAnswers201WithItsVersionAsTheETag() throws Exception {
        Session session = catalog();

        postAs(session, "/api/v1/offerings", """
                {"code":"massage-60","name":"Massage 60","kind":"INTERVAL","durationMin":60,"slotStep":30,"horizonDays":30}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("massage-60"))
                .andExpect(jsonPath("$.name").value("Massage 60"))
                .andExpect(jsonPath("$.kind").value("INTERVAL"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.durationMin").value(60))
                .andExpect(jsonPath("$.durationStep").value(1))
                .andExpect(jsonPath("$.slotStep").value(30))
                .andExpect(jsonPath("$.bufferBefore").value(0))
                .andExpect(jsonPath("$.leadTimeSeconds").value(0))
                .andExpect(jsonPath("$.horizonDays").value(30))
                .andExpect(jsonPath("$.maxQty").value(1))
                .andExpect(jsonPath("$.publicShowsRemaining").value(false))
                .andExpect(jsonPath("$.queueMode").value("NONE"))
                .andExpect(jsonPath("$.admitPerSecond").value(50))
                .andExpect(jsonPath("$.admitWindowSeconds").value(120))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.requirements.length()").value(0))
                .andExpect(jsonPath("$.cancellationWindows.length()").value(0));
    }

    @Test
    void aDuplicateCodeIsRefusedWith409() throws Exception {
        Session session = catalog();
        createOffering(session, "taken");

        postAs(session, "/api/v1/offerings", offeringBody("taken", "Another", "INTERVAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
    }

    @Test
    void anInvalidDurationIsRefusedWith422() throws Exception {
        Session session = catalog();

        postAs(session, "/api/v1/offerings", """
                {"code":"bad-duration","name":"Bad","kind":"INTERVAL","durationMin":0,"slotStep":30,"horizonDays":30}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aPatchWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = catalog();
        String id = createOffering(session, "conditional");

        patchAs(session, "/api/v1/offerings/{id}", "{\"name\":\"Renamed\"}", null, id)
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void aPatchChangesOnlyThePropertiesItCarriesAndBumpsTheVersion() throws Exception {
        Session session = catalog();
        String id = createOffering(session, "patchable");

        patchAs(session, "/api/v1/offerings/{id}", "{\"name\":\"Renamed\",\"status\":\"ACTIVE\"}", "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.durationMin").value(60));

        getAs(session, "/api/v1/offerings/{id}", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void aStaleIfMatchIsRefusedWith412() throws Exception {
        Session session = catalog();
        String id = createOffering(session, "stale");
        patchAs(session, "/api/v1/offerings/{id}", "{\"name\":\"Renamed\"}", "\"1\"", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        patchAs(session, "/api/v1/offerings/{id}", "{\"name\":\"Again\"}", "\"1\"", id)
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string("ETag", "\"2\""));

        getAs(session, "/api/v1/offerings/{id}", id).andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void theListFiltersSortsAndPagesByCursor() throws Exception {
        Session session = catalog();
        createOffering(session, "beta", "Beta", "INTERVAL");
        createOffering(session, "alpha", "Alpha", "INTERVAL");
        createOffering(session, "gamma", "Gamma", "INTERVAL");

        String firstPage = listOfferings(
                        session,
                        "filter[status][eq]", "DRAFT",
                        "sort", "name",
                        "limit", "2",
                        "page_info", "cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Alpha"))
                .andExpect(jsonPath("$.data[1].name").value("Beta"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode next = JSON.readTree(firstPage).get("page").get("next_cursor");
        assertThat(next).as("the first page is not the last one").isNotNull();

        listOfferings(
                        session,
                        "filter[status][eq]", "DRAFT",
                        "sort", "name",
                        "limit", "2",
                        "page_info", "cursor",
                        "cursor", next.asString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Gamma"))
                .andExpect(jsonPath("$.page.next_cursor").doesNotExist());
    }

    @Test
    void archivingIs204AndLeavesTheOfferingArchived() throws Exception {
        Session session = catalog();
        String id = createOffering(session, "archivable");

        deleteAs(session, "/api/v1/offerings/{id}", "\"1\"", id).andExpect(status().isNoContent());

        getAs(session, "/api/v1/offerings/{id}", id)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        deleteAs(session, "/api/v1/offerings/{id}", "\"2\"", id)
                .andExpect(status().isNoContent());
        getAs(session, "/api/v1/offerings/{id}", id).andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void replacingRequirementsStoresTheSetAndGetReturnsIt() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-1");
        String id = createOffering(session, "interval");

        putAs(session, "/api/v1/offerings/{id}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements.length()").value(1))
                .andExpect(jsonPath("$.requirements[0].slotCode").value("staff"))
                .andExpect(jsonPath("$.requirements[0].resourceId").value(resource.toString()));

        getAs(session, "/api/v1/offerings/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements.length()").value(1))
                .andExpect(jsonPath("$.requirements[0].resourceId").value(resource.toString()));
    }

    @Test
    void aCapacityOfferingRefusesRequirementsWith422() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "seat-1");
        String id = createOffering(session, "capacity", "Capacity", "CAPACITY");

        putAs(session, "/api/v1/offerings/{id}/requirements", """
                {"requirements":[{"slotCode":"seat","resourceId":"%s"}]}
                """.formatted(resource), id)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_REQUIREMENTS"));
    }

    @Test
    void aResourceNamedTwiceIsRefusedWith422() throws Exception {
        Session session = catalog();
        UUID resource = seedResource(session, "room-2");
        String id = createOffering(session, "twice");

        putAs(session, "/api/v1/offerings/{id}/requirements", """
                {"requirements":[{"slotCode":"one","resourceId":"%s"},{"slotCode":"two","resourceId":"%s"}]}
                """.formatted(resource, resource), id)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_REQUIREMENTS"));
    }

    @Test
    void cancellationWindowsAreStoredLongestNoticeFirst() throws Exception {
        Session session = catalog();
        String id = createOffering(session, "cancellable");

        putAs(session, "/api/v1/offerings/{id}/cancellation-windows", """
                {"windows":[{"minutesBeforeStart":60,"code":"late"},{"minutesBeforeStart":1440,"code":"full"}]}
                """, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows.length()").value(2))
                .andExpect(jsonPath("$.windows[0].minutesBeforeStart").value(1440))
                .andExpect(jsonPath("$.windows[0].code").value("full"))
                .andExpect(jsonPath("$.windows[1].minutesBeforeStart").value(60))
                .andExpect(jsonPath("$.windows[1].code").value("late"));
    }

    @Test
    void anotherTenantsOfferingIs404() throws Exception {
        Session one = catalog();
        Session other = catalog();
        String id = createOffering(one, "private");

        getAs(other, "/api/v1/offerings/{id}", id).andExpect(status().isNotFound());
        patchAs(other, "/api/v1/offerings/{id}", "{\"name\":\"Mine\"}", "\"1\"", id)
                .andExpect(status().isNotFound());
    }

    private String createOffering(Session session, String code) throws Exception {
        return createOffering(session, code, "Offering " + code, "INTERVAL");
    }

    private String createOffering(Session session, String code, String name, String kind) throws Exception {
        String response = postAs(session, "/api/v1/offerings", offeringBody(code, name, kind))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(response).get("id").asString();
    }

    private static String offeringBody(String code, String name, String kind) {
        return """
                {"code":"%s","name":"%s","kind":"%s","durationMin":60,"slotStep":30,"horizonDays":30}
                """.formatted(code, name, kind);
    }

    private ResultActions listOfferings(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/offerings")
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
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
