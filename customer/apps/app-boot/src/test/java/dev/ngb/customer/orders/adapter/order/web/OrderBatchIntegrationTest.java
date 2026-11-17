package dev.ngb.customer.orders.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order batch: each order in its own transaction, each order reported on its own.
 *
 * <p>The cases that decide the design are here: a bad order fails alone and leaves the others stored, the same batch
 * again replays every order it already recorded, and only the envelope itself fails the whole call.
 */
class OrderBatchIntegrationTest extends OrderApiTestCase {

    @Test
    void aBatchReportsEachOrderAndStoresTheGoodOnes() throws Exception {
        Session session = tenant();
        Instant occurredAt = past();
        String good = order("cust-batch", occurredAt, List.of(line("SKU-1", "1", 500)));
        String alreadyRecorded = order("cust-batch", occurredAt, List.of(line("SKU-2", "1", 700)));
        String bad = order("cust-batch", occurredAt, List.of(line("SKU-3", "0", 100)));

        putOrder(session, "order-batch-2", alreadyRecorded).andExpect(status().isCreated());

        batch(session, """
                {"orders":[
                  {"orderRef":"order-batch-1",%s},
                  {"orderRef":"order-batch-2",%s},
                  {"orderRef":"order-batch-3",%s},
                  {"externalRef":"cust-batch","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos",
                   "total":100,"createCustomer":true,"lines":[%s]}
                ]}
                """.formatted(
                        members(good),
                        members(alreadyRecorded),
                        members(bad),
                        occurredAt,
                        line("SKU-4", "1", 100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].ref").value("order-batch-1"))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[1].outcome").value("REPLAYED"))
                .andExpect(jsonPath("$.results[2].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[2].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.results[3].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[3].code").value("VALIDATION_FAILED"));

        assertThat(orderRows(session))
                .as("the two good orders are stored and the failed ones are not")
                .isEqualTo(2);
        assertThat(outboxRows(session, "order.recorded")).isEqualTo(2);
    }

    @Test
    void theSameBatchAgainReplaysEveryOrderItRecorded() throws Exception {
        Session session = tenant();
        Instant occurredAt = past();
        String body = """
                {"orders":[
                  {"orderRef":"order-again-1",%s},
                  {"orderRef":"order-again-2",%s}
                ]}
                """.formatted(
                members(order("cust-again", occurredAt, List.of(line("SKU-1", "1", 500)))),
                members(order("cust-again", occurredAt, List.of(line("SKU-2", "2", 900)))));

        batch(session, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[1].outcome").value("CREATED"));

        batch(session, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REPLAYED"))
                .andExpect(jsonPath("$.results[1].outcome").value("REPLAYED"));

        assertThat(orderRows(session)).isEqualTo(2);
        assertThat(outboxRows(session, "order.recorded")).isEqualTo(2);
        assertThat(outboxRows(session, "customer.created")).isEqualTo(1);
    }

    @Test
    void anEnvelopeOutsideTheLimitsFailsTheWholeCall() throws Exception {
        Session session = tenant();
        Instant occurredAt = past();

        batch(session, "{\"orders\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        List<String> many = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            many.add("{\"orderRef\":\"order-limit-%d\",%s}"
                    .formatted(index, members(order("cust-limit", occurredAt, List.of(line("SKU-1", "1", 100))))));
        }
        batch(session, "{\"orders\":[" + String.join(",", many) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));
        assertThat(orderRows(session)).isZero();
    }

    @Test
    void aBatchOverTheLineLimitFailsTheWholeCall() throws Exception {
        Session session = tenant();
        Instant occurredAt = past();
        List<String> orders = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            orders.add("{\"orderRef\":\"order-lines-%d\",%s}"
                    .formatted(index, members(bulkOrder("cust-lines", occurredAt, 300))));
        }

        batch(session, "{\"orders\":[" + String.join(",", orders) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));
        assertThat(orderRows(session)).isZero();
    }

    /** The members of an order body without its outer braces, so an item can carry its own reference in front of them. */
    private static String members(String orderBody) {
        String text = orderBody.strip();
        return text.substring(1, text.length() - 1).strip();
    }
}
