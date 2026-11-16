package dev.ngb.crm.activity.adapter.activity.web;

import static dev.ngb.crm_jooq.Tables.ACTIVITIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.support.CrmApiTest;
import dev.ngb.crm.support.TestTokenFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The activity surface: appending one, replaying it by its caller's reference, appending a batch and reading one
 * customer's timeline, over the real filter chain and the real database.
 *
 * <p>The cases that decide the design are here: a retried reference must replay the stored activity instead of
 * appending the same fact twice, a different body under one reference must be a conflict, and two unrelated types must
 * both be readable without a change to the code that serves them.
 */
class ActivityApiIntegrationTest extends CrmApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A tenant whose credential holds every code these cases need. */
    private Session tenant() {
        return tenant(
                CrmPermissions.CUSTOMER_MANAGE,
                CrmPermissions.CUSTOMER_READ,
                CrmPermissions.ACTIVITY_RECORD,
                CrmPermissions.ACTIVITY_READ);
    }

    @Test
    void anAppendedActivityIsReadBackWithEverythingTheWriterSent() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-1").andExpect(status().isCreated());

        postActivity(session, """
                {"externalRef":"cust-1","type":"pos.receipt-printed","occurredAt":"2026-11-16T10:15:30Z",
                 "source":"tenant-pos","payload":{"receipt":"R-1","lines":2},"activityRef":"act-1"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRef").value("cust-1"))
                .andExpect(jsonPath("$.type").value("pos.receipt-printed"))
                .andExpect(jsonPath("$.source").value("tenant-pos"))
                .andExpect(jsonPath("$.payload.receipt").value("R-1"))
                .andExpect(jsonPath("$.payload.lines").value(2))
                .andExpect(jsonPath("$.activityRef").value("act-1"))
                .andExpect(jsonPath("$.createdAt").isString());

        assertThat(rows(session)).isEqualTo(1);
    }

    @Test
    void anActivityOnACustomerThisTenantDoesNotHaveIsRefusedWith422() throws Exception {
        Session session = tenant();

        postActivity(session, """
                {"externalRef":"cust-nowhere","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CUSTOMER_UNKNOWN"));

        assertThat(rows(session)).isZero();
    }

    @Test
    void theSameReferenceWithTheSameBodyReplaysTheStoredActivity() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-replay").andExpect(status().isCreated());
        String body = """
                {"externalRef":"cust-replay","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "payload":{"screen":"home"},"activityRef":"act-replay"}
                """;

        String first = postActivity(session, body)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String second = postActivity(session, body)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(JSON.readTree(second).get("id").asString())
                .as("a replay answers the activity that was stored first")
                .isEqualTo(JSON.readTree(first).get("id").asString());
        assertThat(rows(session)).isEqualTo(1);
    }

    @Test
    void theSameReferenceWithAnotherBodyIsAConflict() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-conflict").andExpect(status().isCreated());

        postActivity(session, """
                {"externalRef":"cust-conflict","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "activityRef":"act-conflict"}
                """)
                .andExpect(status().isCreated());
        postActivity(session, """
                {"externalRef":"cust-conflict","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "payload":{"screen":"cart"},"activityRef":"act-conflict"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(rows(session)).isEqualTo(1);
    }

    @Test
    void aTypeSourcePayloadOrDateOutsideTheDocumentedShapeIsRefusedWith422() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-invalid").andExpect(status().isCreated());

        postActivity(session, """
                {"externalRef":"cust-invalid","type":"Not A Type","occurredAt":"2026-11-16T10:15:30Z"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postActivity(session, """
                {"externalRef":"cust-invalid","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "source":"%s"}
                """.formatted("x".repeat(65)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postActivity(session, """
                {"externalRef":"cust-invalid","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "payload":"not an object"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        postActivity(session, """
                {"externalRef":"cust-invalid","type":"app.opened","occurredAt":"2026-11-16T10:15:30Z",
                 "payload":{"blob":"%s"}}
                """.formatted("x".repeat(9000)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        postActivity(session, """
                {"externalRef":"cust-invalid","type":"app.opened","occurredAt":"tomorrow"}
                """)
                .andExpect(status().isBadRequest());

        assertThat(rows(session)).isZero();
    }

    @Test
    void theBatchReportsEveryItemOnItsOwnAndAppliesTheGoodOnes() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-batch").andExpect(status().isCreated());

        postAs(session, "/api/v1/activities/batch", """
                {"items":[
                  {"externalRef":"cust-batch","type":"pos.receipt-printed","occurredAt":"2026-11-16T10:00:00Z",
                   "activityRef":"batch-1"},
                  {"externalRef":"cust-missing","type":"app.opened","occurredAt":"2026-11-16T10:01:00Z",
                   "activityRef":"batch-2"},
                  {"externalRef":"cust-batch","type":"app.opened","occurredAt":"2026-11-16T10:02:00Z"}
                ]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].ref").value("batch-1"))
                .andExpect(jsonPath("$.results[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[1].code").value("CUSTOMER_UNKNOWN"))
                .andExpect(jsonPath("$.results[2].outcome").value("CREATED"));

        assertThat(rows(session)).isEqualTo(2);

        postAs(session, "/api/v1/activities/batch", """
                {"items":[
                  {"externalRef":"cust-batch","type":"pos.receipt-printed","occurredAt":"2026-11-16T10:00:00Z",
                   "activityRef":"batch-1"}
                ]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REPLAYED"));
        assertThat(rows(session)).as("a retried batch appends nothing new").isEqualTo(2);
    }

    @Test
    void aBatchOutsideItsCapOrWithoutItemsIsRefused() throws Exception {
        Session session = tenant();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            items.append(i == 0 ? "" : ",");
            items.append("{\"externalRef\":\"cust-cap\",\"type\":\"app.opened\",")
                    .append("\"occurredAt\":\"2026-11-16T10:00:00Z\"}");
        }

        postAs(session, "/api/v1/activities/batch", "{\"items\":[" + items + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));
        postAs(session, "/api/v1/activities/batch", "{\"items\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theTimelineIsNewestFirstAndNarrowsByTypeAndWindow() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-timeline").andExpect(status().isCreated());
        postActivity(session, activity("cust-timeline", "app.opened", "2026-11-16T08:00:00Z"));
        postActivity(session, activity("cust-timeline", "pos.receipt-printed", "2026-11-16T09:00:00Z"));
        postActivity(session, activity("cust-timeline", "app.opened", "2026-11-16T10:00:00Z"));

        timeline(session, "cust-timeline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].occurredAt").value("2026-11-16T10:00:00Z"))
                .andExpect(jsonPath("$.data[2].occurredAt").value("2026-11-16T08:00:00Z"));

        timeline(session, "cust-timeline", "type", "app.opened")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("app.opened"));
        timeline(session, "cust-timeline", "from", "2026-11-16T08:30:00Z", "to", "2026-11-16T09:30:00Z")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("pos.receipt-printed"));
    }

    @Test
    void theTimelinePagesByCursorAndRefusesACursorOfAnotherFilter() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-page").andExpect(status().isCreated());
        postActivity(session, activity("cust-page", "app.opened", "2026-11-16T08:00:00Z"));
        postActivity(session, activity("cust-page", "app.opened", "2026-11-16T09:00:00Z"));
        postActivity(session, activity("cust-page", "app.opened", "2026-11-16T10:00:00Z"));

        String first = timeline(session, "cust-page", "limit", "2")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cursor = JSON.readTree(first).get("page").get("next_cursor").asString();
        assertThat(cursor).isNotBlank();

        timeline(session, "cust-page", "limit", "2", "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].occurredAt").value("2026-11-16T08:00:00Z"));

        timeline(session, "cust-page", "limit", "2", "type", "pos.receipt-printed", "cursor", cursor)
                .andExpect(status().isBadRequest());
    }

    @Test
    void theTimelineOfACustomerThisTenantDoesNotHaveIs404() throws Exception {
        Session session = tenant();

        timeline(session, "cust-nowhere").andExpect(status().isNotFound());
    }

    /** A new source posts a new type and is read back with no change between the two. */
    @Test
    void twoUnrelatedTypesAreReadBackWithNoChangeBetweenThem() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-types").andExpect(status().isCreated());

        postActivity(session, """
                {"externalRef":"cust-types","type":"pos.receipt-printed","occurredAt":"2026-11-16T09:00:00Z",
                 "payload":{"receipt":"R-9"}}
                """)
                .andExpect(status().isCreated());
        postActivity(session, """
                {"externalRef":"cust-types","type":"app.opened","occurredAt":"2026-11-16T09:05:00Z",
                 "payload":{"screen":"orders"}}
                """)
                .andExpect(status().isCreated());

        timeline(session, "cust-types")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("app.opened"))
                .andExpect(jsonPath("$.data[0].payload.screen").value("orders"))
                .andExpect(jsonPath("$.data[1].type").value("pos.receipt-printed"))
                .andExpect(jsonPath("$.data[1].payload.receipt").value("R-9"));
    }

    @Test
    void anotherTenantSeesNeitherTheCustomerNorTheActivity() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-isolated").andExpect(status().isCreated());
        postActivity(session, activity("cust-isolated", "app.opened", "2026-11-16T10:00:00Z"))
                .andExpect(status().isCreated());

        Session other = tenant();
        timeline(other, "cust-isolated").andExpect(status().isNotFound());
        postActivity(other, activity("cust-isolated", "app.opened", "2026-11-16T11:00:00Z"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CUSTOMER_UNKNOWN"));
        assertThat(rows(other)).isZero();
    }

    @Test
    void theSurfacesNeedTheirOwnCodes() throws Exception {
        Session session = tenant(CrmPermissions.ACTIVITY_READ);
        UUID system = replica.tenantSystem(session.tenant().value(), "Reader").value();
        String token = TestTokenFactory.tenantSystemToken(
                system, session.tenant().value(), CrmPermissions.ACTIVITY_READ);

        send(get("/api/v1/customers/{ref}/activities", "cust-any"), session, null, token, null)
                .andExpect(status().isNotFound());

        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activity("cust-any", "app.opened", "2026-11-16T10:00:00Z")),
                session, null, token, null)
                .andExpect(status().isForbidden());
    }

    private static String activity(String externalRef, String type, String occurredAt) {
        return """
                {"externalRef":"%s","type":"%s","occurredAt":"%s"}
                """.formatted(externalRef, type, occurredAt);
    }

    private ResultActions putCustomer(Session session, String externalRef) throws Exception {
        return send(put("/api/v1/customers/{externalRef}", externalRef), session, "{\"displayName\":\"Ada\"}", null);
    }

    private ResultActions postActivity(Session session, String body) throws Exception {
        return postAs(session, "/api/v1/activities", body);
    }

    private ResultActions timeline(Session session, String externalRef, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/customers/{externalRef}/activities", externalRef);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return send(request, session, null, null);
    }

    private long rows(Session session) {
        return rowsOf(session, ACTIVITIES);
    }
}
