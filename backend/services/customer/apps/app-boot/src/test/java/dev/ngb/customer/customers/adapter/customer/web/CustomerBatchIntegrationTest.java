package dev.ngb.customer.customers.adapter.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The batch endpoint: each item in its own transaction, one result per item in the order sent, and a bad item that fails
 * alone instead of taking the batch with it.
 *
 * <p>A Tenant's backfill is the caller this is built for: it retries the whole batch after a partial run, which is safe
 * because every item is idempotent by its reference.
 */
class CustomerBatchIntegrationTest extends CustomerApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The default cap on how many items one call may carry. */
    private static final int CAP = 1000;

    @Test
    void oneBadItemFailsAloneAndTheOthersAreWritten() throws Exception {
        Session session = tenant();

        JsonNode results = results(batchCustomers(session, """
                {"items":[
                  {"externalRef":"cust-good-1","displayName":"Ada Lovelace"},
                  {"externalRef":"cust-bad","displayName":"   "},
                  {"externalRef":"cust-good-2","email":"grace@example.com"}
                ]}
                """)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(results.size()).isEqualTo(3);
        assertThat(results.get(0).get("index").asInt()).isZero();
        assertThat(results.get(0).get("ref").asString()).isEqualTo("cust-good-1");
        assertThat(results.get(0).get("outcome").asString()).isEqualTo("CREATED");
        assertThat(results.get(1).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(results.get(1).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(results.get(2).get("outcome").asString()).isEqualTo("CREATED");

        getCustomer(session, "cust-good-1").andExpect(status().isOk());
        getCustomer(session, "cust-good-2").andExpect(status().isOk());
        getCustomer(session, "cust-bad").andExpect(status().isNotFound());
    }

    @Test
    void twoItemsWithOneReferenceApplyInOrder() throws Exception {
        Session session = tenant();

        JsonNode results = results(batchCustomers(session, """
                {"items":[
                  {"externalRef":"cust-twice","displayName":"First"},
                  {"externalRef":"cust-twice","displayName":"Second"}
                ]}
                """)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(results.get(0).get("outcome").asString()).isEqualTo("CREATED");
        assertThat(results.get(1).get("outcome").asString()).isEqualTo("UPDATED");
        getCustomer(session, "cust-twice")
                .andExpect(jsonPath("$.displayName").value("Second"))
                .andExpect(jsonPath("$.externalRef").value("cust-twice"));
        assertThat(outboxRows(session, "customer.created")).isEqualTo(1);
    }

    @Test
    void aRetriedBatchIsUnchanged() throws Exception {
        Session session = tenant();
        String body = """
                {"items":[
                  {"externalRef":"cust-retry-1","displayName":"Ada"},
                  {"externalRef":"cust-retry-2","displayName":"Grace"}
                ]}
                """;

        batchCustomers(session, body).andExpect(status().isOk());
        JsonNode results = results(batchCustomers(session, body)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(outcomes(results)).containsExactly("UNCHANGED", "UNCHANGED");
        assertThat(outboxRows(session, "customer.created")).isEqualTo(2);
        assertThat(customerRows(session)).isEqualTo(2);
    }

    @Test
    void anEnvelopeWithoutItemsIsRefusedWith400() throws Exception {
        Session session = tenant();

        batchCustomers(session, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        batchCustomers(session, "{\"items\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBatchOverTheCapIsRefusedWith400AndWritesNothing() throws Exception {
        Session session = tenant();

        batchCustomers(session, batchOf(CAP + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));
        assertThat(customerRows(session)).isZero();
    }

    @Test
    void aBatchAtTheCapReportsEveryItem() throws Exception {
        Session session = tenant();

        JsonNode results = results(batchCustomers(session, batchOf(CAP))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(results.size()).isEqualTo(CAP);
        assertThat(outcomes(results)).allMatch("CREATED"::equals);
        assertThat(customerRows(session)).isEqualTo(CAP);
        assertThat(outboxRows(session, "customer.created")).isEqualTo(CAP);
    }

    @Test
    void anItemWithoutAReferenceFailsAlone() throws Exception {
        Session session = tenant();

        JsonNode results = results(batchCustomers(session, """
                {"items":[
                  {"displayName":"Nobody"},
                  {"externalRef":"cust-present","displayName":"Ada"}
                ]}
                """)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(results.get(0).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(results.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(results.get(0).get("ref").isNull()).isTrue();
        assertThat(results.get(1).get("outcome").asString()).isEqualTo("CREATED");
    }

    private static String batchOf(int items) {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"externalRef\":\"cust-batch-").append(i).append("\"}");
        }
        return body.append("]}").toString();
    }

    private static JsonNode results(String body) {
        return JSON.readTree(body).get("results");
    }

    private static List<String> outcomes(JsonNode results) {
        Map<Integer, String> byIndex = new LinkedHashMap<>();
        for (JsonNode result : results) {
            byIndex.put(result.get("index").asInt(), result.get("outcome").asString());
        }
        return List.copyOf(byIndex.values());
    }
}
