package dev.ngb.customer.orders.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.customer.orders.application.order.exception.InvalidOrderException;
import dev.ngb.customer.orders.application.order.exception.LineLimitExceededException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order endpoint: record, replay and read, over the real filter chain, the real database and the real outbox.
 *
 * <p>The cases that decide the design are here: a repeated identical body must be a no-op that announces nothing, a
 * changed body under the same reference is a conflict, and fifty callers recording the same order at once must end
 * with one order and one announcement.
 */
class OrderApiIntegrationTest extends OrderApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void putRecordsTheOrderAndAnswersItsVersionAsTheETag() throws Exception {
        Session session = tenant();
        String body = order("cust-order-1", past(), List.of(line("SKU-1", "2.000", 1200), line("SKU-2", "1", 100)));

        putOrder(session, "order-1", body)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.orderRef").value("order-1"))
                .andExpect(jsonPath("$.externalRef").value("cust-order-1"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.total").value(1300))
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.orderVersion").value(1))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].earnEligible").value(true))
                .andExpect(jsonPath("$.returns.length()").value(0));

        getOrder(session, "order-1")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.externalRef").value("cust-order-1"))
                .andExpect(jsonPath("$.lines[1].sku").value("SKU-2"));

        assertThat(orderRows(session)).isEqualTo(1);
        assertThat(orderLineRows(session)).isEqualTo(2);
        assertThat(outboxRows(session, "order.recorded")).isEqualTo(1);
        assertThat(outboxRows(session, "customer.created"))
                .as("an order that creates its customer announces it once")
                .isEqualTo(1);
    }

    @Test
    void anIdenticalReplayChangesNothingAndAnnouncesTheOrderOnce() throws Exception {
        Session session = tenant();
        String body = order("cust-replay", past(), List.of(line("SKU-1", "1", 500)));

        putOrder(session, "order-replay", body).andExpect(status().isCreated());
        putOrder(session, "order-replay", body)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.orderVersion").value(1));

        assertThat(orderRows(session)).isEqualTo(1);
        assertThat(outboxRows(session, "order.recorded"))
                .as("an identical body is not a new fact and must not be announced again")
                .isEqualTo(1);
    }

    @Test
    void aChangedBodyUnderTheSameReferenceIsRefusedWith409() throws Exception {
        Session session = tenant();
        putOrder(session, "order-changed", order("cust-changed", past(), List.of(line("SKU-1", "1", 500))))
                .andExpect(status().isCreated());

        putOrder(session, "order-changed", order("cust-changed", past(), List.of(line("SKU-1", "2", 900))))
                .andExpect(status().isConflict());

        getOrder(session, "order-changed")
                .andExpect(jsonPath("$.lines[0].quantity").value(1.0))
                .andExpect(jsonPath("$.orderVersion").value(1));
        assertThat(outboxRows(session, "order.recorded")).isEqualTo(1);
    }

    @Test
    void anUnknownCustomerIsRefusedUnlessTheOrderCreatesIt() throws Exception {
        Session session = tenant();
        String refused = order("cust-nowhere", past(), 500L, false, List.of(line("SKU-1", "1", 500)));

        putOrder(session, "order-unknown", refused)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CUSTOMER_UNKNOWN"));
        assertThat(orderRows(session)).isZero();
        assertThat(outboxRows(session, "customer.created")).isZero();

        putOrder(session, "order-unknown", order("cust-nowhere", past(), 500L, true, List.of(line("SKU-1", "1", 500))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRef").value("cust-nowhere"));
        assertThat(outboxRows(session, "customer.created")).isEqualTo(1);
    }

    @Test
    void anOrderWithMoreLinesThanTheCapIsRefused() throws Exception {
        Session session = tenant();

        putOrder(session, "order-huge", bulkOrder("cust-huge", past(), 501))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value(LineLimitExceededException.CODE));
        assertThat(orderRows(session)).isZero();
        assertThat(outboxRows(session, "order.recorded")).isZero();
    }

    @Test
    void anOrderThatBreaksARuleIsRefusedWith422() throws Exception {
        Session session = tenant();
        Instant occurredAt = past();

        putOrder(session, "order-bad-amount",
                        order("cust-bad", occurredAt, 500L, true, List.of(line("SKU-1", "1", -1))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value(InvalidOrderException.CODE));
        putOrder(session, "order-bad-quantity",
                        order("cust-bad", occurredAt, 500L, true, List.of(line("SKU-1", "0", 100))))
                .andExpect(status().is(422));
        putOrder(session, "order-bad-currency", """
                        {"externalRef":"cust-bad","currency":"eur","occurredAt":"%s","storeCode":"S1","channel":"pos",
                         "total":100,"createCustomer":true,"lines":[%s]}
                        """.formatted(occurredAt, line("SKU-1", "1", 100)))
                .andExpect(status().is(422));
        putOrder(session, "order-future", order(
                        "cust-bad", Instant.now().plus(1, ChronoUnit.HOURS), 500L, true, List.of(line("SKU-1", "1", 100))))
                .andExpect(status().is(422));
        putOrder(session, "order-no-lines", """
                        {"externalRef":"cust-bad","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos",
                         "total":100,"createCustomer":true,"lines":[]}
                        """.formatted(occurredAt))
                .andExpect(status().is(422));

        assertThat(orderRows(session)).isZero();
        assertThat(outboxRows(session, "order.recorded")).isZero();
    }

    @Test
    void fiftyParallelIdenticalPutsCreateOneOrderAndOneEvent() throws Exception {
        Session session = tenant();
        String body = order("cust-race", past(), List.of(line("SKU-1", "1", 500)));
        int callers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> answers = new ArrayList<>(callers);
            for (int i = 0; i < callers; i++) {
                Callable<Integer> caller = () -> {
                    start.await();
                    return statusCode(putOrder(session, "order-race", body));
                };
                answers.add(pool.submit(caller));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(callers);
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(120, TimeUnit.SECONDS));
            }
            assertThat(statuses).allMatch(code -> code == 201 || code == 200);
            assertThat(statuses).filteredOn(code -> code == 201)
                    .as("exactly one caller recorded the order")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(orderRows(session)).as("the unique reference admits one order").isEqualTo(1);
        assertThat(outboxRows(session, "order.recorded"))
                .as("the order is announced once, however many callers raced")
                .isEqualTo(1);
        assertThat(outboxRows(session, "customer.created"))
                .as("the customer the order created is announced once too")
                .isEqualTo(1);
        getOrder(session, "order-race").andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void theRecordedEventCarriesTheDocumentedShapeAndTheCompleteState() throws Exception {
        Session session = tenant();
        putOrder(session, "order-event", order("cust-event", past(), List.of(line("SKU-1", "3.000", 1200))))
                .andExpect(status().isCreated());

        JsonNode payload = JSON.readTree(outboxPayload(session, "order.recorded"));
        assertThat(names(payload))
                .as("the payload carries the documented fields and nothing else")
                .isEqualTo(names(JSON.readTree(read("/events/order.recorded.schema.json")).get("properties")));
        assertThat(payload.get("orderVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("externalRef").asString()).isEqualTo("cust-event");
        assertThat(payload.get("currency").asString()).isEqualTo("EUR");
        assertThat(payload.get("lines").size()).isEqualTo(1);
        assertThat(payload.get("lines").get(0).get("returnedQuantity").asDouble()).isZero();
        assertThat(payload.get("lines").get(0).get("earnEligible").asBoolean()).isTrue();
        assertThat(schema("order.recorded").validate(payload))
                .as("the payload matches the committed schema")
                .isEmpty();
    }

    @Test
    void theCustomerOrdersPageIsNewestFirstAndContinuesByCursor() throws Exception {
        Session session = tenant();
        Instant base = past();
        putOrder(session, "order-page-1", order("cust-page", base.minus(2, ChronoUnit.HOURS),
                List.of(line("SKU-1", "1", 100)))).andExpect(status().isCreated());
        putOrder(session, "order-page-2", order("cust-page", base.minus(1, ChronoUnit.HOURS),
                List.of(line("SKU-1", "1", 100)))).andExpect(status().isCreated());
        putOrder(session, "order-page-3", order("cust-page", base, List.of(line("SKU-1", "1", 100))))
                .andExpect(status().isCreated());
        putOrder(session, "order-other", order("cust-other", base, List.of(line("SKU-1", "1", 100))))
                .andExpect(status().isCreated());

        String firstPage = listOrders(session, "cust-page", "limit", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].orderRef").value("order-page-3"))
                .andExpect(jsonPath("$.data[1].orderRef").value("order-page-2"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cursor = JSON.readTree(firstPage).get("page").get("next_cursor").asString();
        assertThat(cursor).as("the first page is not the last one").isNotBlank();

        listOrders(session, "cust-page", "limit", "2", "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderRef").value("order-page-1"))
                .andExpect(jsonPath("$.page.next_cursor").doesNotExist());
    }

    @Test
    void anUnknownOrderAndAnUnknownCustomerAre404() throws Exception {
        Session session = tenant();

        getOrder(session, "order-nowhere")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        listOrders(session, "cust-nowhere").andExpect(status().isNotFound());
    }

    @Test
    void aCredentialWithoutTheRecordCodeCannotWrite() throws Exception {
        Session session = tenant();
        String readOnly = credentialOf(session, "Rival POS", dev.ngb.customer.shared.CustomerPermissions.ORDER_READ);

        putOrder(session, "order-read-only", order("cust-read-only", past(), List.of(line("SKU-1", "1", 100))),
                readOnly)
                .andExpect(status().isForbidden());
        assertThat(orderRows(session)).isZero();

        putOrder(session, "order-owner", order("cust-owner", past(), List.of(line("SKU-1", "1", 100))))
                .andExpect(status().isCreated());
        getOrder(session, "order-owner").andExpect(status().isOk());
    }

    private static Schema schema(String type) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.readTree(read("/events/" + type + ".schema.json")));
    }

    private static String read(String resource) {
        try (InputStream stream = OrderApiIntegrationTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
