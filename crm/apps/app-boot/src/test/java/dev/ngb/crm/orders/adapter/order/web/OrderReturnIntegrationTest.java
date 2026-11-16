package dev.ngb.crm.orders.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.crm.orders.application.returns.exception.ReturnExceedsSaleException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The return endpoint: a return takes part of an order back, once, and every return is announced with the order's new
 * state.
 *
 * <p>The cases that decide the design are here: a replay moves nothing and announces nothing, a return above what was
 * sold is refused and leaves the order untouched, and two returns of the last unit cannot both succeed.
 */
class OrderReturnIntegrationTest extends OrderApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aReturnTakesTheLinesBackAndRaisesTheOrdersVersion() throws Exception {
        Session session = tenant();
        putOrder(session, "order-ret", order("cust-ret", past(),
                List.of(line("SKU-1", "2.000", 1000), line("SKU-2", "1", 300))))
                .andExpect(status().isCreated());

        putReturn(session, "order-ret", "return-1", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.orderVersion").value(2))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(1.0))
                .andExpect(jsonPath("$.lines[0].returnedAmount").value(500))
                .andExpect(jsonPath("$.lines[1].returnedQuantity").value(0))
                .andExpect(jsonPath("$.returns.length()").value(1))
                .andExpect(jsonPath("$.returns[0].returnRef").value("return-1"))
                .andExpect(jsonPath("$.returns[0].lines[0].lineNo").value(1));

        getOrder(session, "order-ret")
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"));
        assertThat(returnRows(session)).isEqualTo(1);

        // Taking the rest back in full makes the order RETURNED rather than partial.
        putReturn(session, "order-ret", "return-2", returnBody(past(),
                        returnedLine(1, "1.000", 500), returnedLine(2, "1", 300)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(2.0));
    }

    @Test
    void aReplayedReturnChangesNothingAndAnnouncesNothing() throws Exception {
        Session session = tenant();
        putOrder(session, "order-return-replay", order("cust-return-replay", past(),
                List.of(line("SKU-1", "2.000", 1000)))).andExpect(status().isCreated());
        String body = returnBody(past(), returnedLine(1, "1.000", 500));

        putReturn(session, "order-return-replay", "return-repeat", body).andExpect(status().isCreated());
        putReturn(session, "order-return-replay", "return-repeat", body)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.orderVersion").value(2))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(1.0));

        assertThat(returnRows(session)).isEqualTo(1);
        assertThat(outboxRows(session, "order.refunded"))
                .as("a replay is not a new fact and must not be announced again")
                .isEqualTo(1);
    }

    @Test
    void aChangedReturnBodyUnderTheSameReferenceIs409() throws Exception {
        Session session = tenant();
        putOrder(session, "order-return-changed", order("cust-return-changed", past(),
                List.of(line("SKU-1", "3.000", 900)))).andExpect(status().isCreated());
        putReturn(session, "order-return-changed", "return-changed", returnBody(past(), returnedLine(1, "1.000", 300)))
                .andExpect(status().isCreated());

        putReturn(session, "order-return-changed", "return-changed", returnBody(past(), returnedLine(1, "2.000", 600)))
                .andExpect(status().isConflict());

        getOrder(session, "order-return-changed").andExpect(jsonPath("$.orderVersion").value(2));
        assertThat(outboxRows(session, "order.refunded")).isEqualTo(1);
    }

    @Test
    void aReturnAboveTheSaleIsRefusedAndLeavesTheOrderUnchanged() throws Exception {
        Session session = tenant();
        putOrder(session, "order-over", order("cust-over", past(), List.of(line("SKU-1", "1.000", 500))))
                .andExpect(status().isCreated());

        putReturn(session, "order-over", "return-over", returnBody(past(), returnedLine(1, "2.000", 500)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value(ReturnExceedsSaleException.CODE));
        putReturn(session, "order-over", "return-money", returnBody(past(), returnedLine(1, "1.000", 501)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value(ReturnExceedsSaleException.CODE));
        putReturn(session, "order-over", "return-line", returnBody(past(), returnedLine(2, "1.000", 100)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        getOrder(session, "order-over")
                .andExpect(jsonPath("$.orderVersion").value(1))
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(0))
                .andExpect(jsonPath("$.returns.length()").value(0));
        assertThat(returnRows(session)).isZero();
        assertThat(outboxRows(session, "order.refunded")).isZero();
    }

    @Test
    void twoParallelReturnsOfTheLastUnitCannotBothSucceed() throws Exception {
        Session session = tenant();
        putOrder(session, "order-last-unit", order("cust-last-unit", past(),
                List.of(line("SKU-1", "1.000", 500)))).andExpect(status().isCreated());
        String body = returnBody(past(), returnedLine(1, "1.000", 500));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> callers = List.of(
                    () -> {
                        start.await();
                        return statusCode(putReturn(session, "order-last-unit", "return-a", body));
                    },
                    () -> {
                        start.await();
                        return statusCode(putReturn(session, "order-last-unit", "return-b", body));
                    });
            List<Future<Integer>> answers = callers.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = answers.stream().map(answer -> {
                try {
                    return answer.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(statuses).as("one return took the unit and the other was refused").containsExactlyInAnyOrder(
                    201, 422);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        getOrder(session, "order-last-unit")
                .andExpect(jsonPath("$.orderVersion").value(2))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(1.0))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returns.length()").value(1));
        assertThat(returnRows(session)).isEqualTo(1);
        assertThat(outboxRows(session, "order.refunded")).isEqualTo(1);
    }

    @Test
    void theSecondRefundedEventCarriesTheCumulativeReturnedFigures() throws Exception {
        Session session = tenant();
        putOrder(session, "order-cumulative", order("cust-cumulative", past(),
                List.of(line("SKU-1", "3.000", 1500)))).andExpect(status().isCreated());
        putReturn(session, "order-cumulative", "return-first", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isCreated());
        putReturn(session, "order-cumulative", "return-second", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isCreated());

        JsonNode second = JSON.readTree(outboxPayload(session, "order.refunded"));
        assertThat(second.get("orderVersion").asInt()).isEqualTo(3);
        assertThat(second.get("lines").get(0).get("returnedQuantity").asDouble()).isEqualTo(2.0);
        assertThat(second.get("lines").get(0).get("returnedAmount").asInt()).isEqualTo(1000);
        assertThat(second.get("return").get("returnRef").asString()).isEqualTo("return-second");
        assertThat(second.get("return").get("lines").get(0).get("quantity").asDouble()).isEqualTo(1.0);
        assertThat(schema("order.refunded").validate(second))
                .as("the refunded payload matches the committed schema")
                .isEmpty();
        assertThat(outboxRows(session, "order.refunded")).isEqualTo(2);
    }

    @Test
    void aReferenceRecordedForAnotherOrderIs409AndAnUnknownOrderIs404() throws Exception {
        Session session = tenant();
        putOrder(session, "order-a", order("cust-ab", past(), List.of(line("SKU-1", "2.000", 1000))))
                .andExpect(status().isCreated());
        putOrder(session, "order-b", order("cust-ab", past(), List.of(line("SKU-1", "2.000", 1000))))
                .andExpect(status().isCreated());
        putReturn(session, "order-a", "return-shared", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isCreated());

        putReturn(session, "order-b", "return-shared", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isConflict());
        putReturn(session, "order-nowhere", "return-nowhere", returnBody(past(), returnedLine(1, "1.000", 500)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertThat(returnRows(session)).isEqualTo(1);
    }

    @Test
    void aReturnThatIsNotAValidBodyIsRefusedWith422() throws Exception {
        Session session = tenant();
        putOrder(session, "order-invalid-return", order("cust-invalid-return", past(),
                List.of(line("SKU-1", "2.000", 1000)))).andExpect(status().isCreated());
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        putReturn(session, "order-invalid-return", "return-empty", returnBody(past()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putReturn(session, "order-invalid-return", "return-zero", returnBody(past(), returnedLine(1, "0", 100)))
                .andExpect(status().is(422));
        putReturn(session, "order-invalid-return", "return-future", returnBody(future, returnedLine(1, "1", 100)))
                .andExpect(status().is(422));
        putReturn(session, "order-invalid-return", "return-twice", returnBody(past(),
                        returnedLine(1, "1.000", 100), returnedLine(1, "1.000", 100)))
                .andExpect(status().is(422));

        assertThat(returnRows(session)).isZero();
        getOrder(session, "order-invalid-return").andExpect(jsonPath("$.orderVersion").value(1));
    }

    private static com.networknt.schema.Schema schema(String type) {
        try (var stream = OrderReturnIntegrationTest.class.getResourceAsStream("/events/" + type + ".schema.json")) {
            if (stream == null) {
                throw new IllegalStateException("the " + type + " schema is not on the classpath");
            }
            return com.networknt.schema.SchemaRegistry.withDefaultDialect(
                            com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
