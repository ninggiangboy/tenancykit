package dev.ngb.customer.orders.adapter.order.web;

import static dev.ngb.customer_jooq.Tables.ORDERS;
import static dev.ngb.customer_jooq.Tables.ORDER_LINES;
import static dev.ngb.customer_jooq.Tables.ORDER_RETURNS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jooq.JSONB;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The order API tests' common ground: a Tenant with a Tenant System credential that holds the two order codes, the
 * request builders the tests send, and the counters they assert against.
 *
 * <p>Most tests let the order create its customer ({@code createCustomer}), because the customer endpoint needs a code
 * these credentials deliberately do not hold; that keeps an order test about orders and proves the seam between the two
 * contexts at the same time.
 */
abstract class OrderApiTestCase extends CustomerApiTest {

    /** A tenant whose credential holds both order codes. */
    Session tenant() {
        return tenant(CustomerPermissions.ORDER_RECORD, CustomerPermissions.ORDER_READ);
    }

    ResultActions putOrder(Session session, String orderRef, String body) throws Exception {
        return send(put("/system/v1/orders/{orderRef}", orderRef), session, body, null);
    }

    ResultActions putOrder(Session session, String orderRef, String body, String token) throws Exception {
        return send(put("/system/v1/orders/{orderRef}", orderRef), session, body, token, null);
    }

    ResultActions getOrder(Session session, String orderRef) throws Exception {
        return send(get("/system/v1/orders/{orderRef}", orderRef), session, null, null);
    }

    ResultActions putReturn(Session session, String orderRef, String returnRef, String body) throws Exception {
        return send(put("/system/v1/orders/{orderRef}/returns/{returnRef}", orderRef, returnRef), session, body, null);
    }

    ResultActions putReturn(Session session, String orderRef, String returnRef, String body, String token)
            throws Exception {
        return send(put("/system/v1/orders/{orderRef}/returns/{returnRef}", orderRef, returnRef), session, body, token,
                null);
    }

    ResultActions batch(Session session, String body) throws Exception {
        return send(post("/system/v1/orders/batch"), session, body, null);
    }

    ResultActions listOrders(Session session, String externalRef, String... params) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/system/v1/customers/{externalRef}/orders", externalRef);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return send(request, session, null, null);
    }

    /** An instant a body can carry: in the past, so a clock that drifts during a test cannot refuse it. */
    static Instant past() {
        return Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    /** One order body; the same string sent twice is the same request, which is what a replay means. */
    static String order(String externalRef, Instant occurredAt, List<String> lines) {
        return order(externalRef, occurredAt, 1300L, true, lines);
    }

    static String order(String externalRef, Instant occurredAt, long total, boolean createCustomer, List<String> lines) {
        return """
                {"externalRef":"%s","currency":"EUR","occurredAt":"%s","storeCode":"S1","channel":"pos",
                 "total":%d,"createCustomer":%s,"lines":[%s]}
                """.formatted(externalRef, occurredAt.toString(), total, createCustomer, String.join(",", lines));
    }

    static String line(String sku, String quantity, long amount) {
        return """
                {"sku":"%s","name":"Item %s","category":"grocery","quantity":%s,"amount":%d}""".formatted(sku, sku,
                quantity, amount);
    }

    /** An order of {@code count} lines, for the cases that only care about the size of an order. */
    static String bulkOrder(String externalRef, Instant occurredAt, int count) {
        List<String> lines = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            lines.add(line("SKU-" + index, "1.000", 100L + index));
        }
        return order(externalRef, occurredAt, count * 100L, true, lines);
    }

    static String returnBody(Instant occurredAt, String... lines) {
        return """
                {"occurredAt":"%s","lines":[%s]}""".formatted(occurredAt.toString(), String.join(",", lines));
    }

    static String returnedLine(int lineNo, String quantity, long amount) {
        return """
                {"lineNo":%d,"quantity":%s,"amount":%d}""".formatted(lineNo, quantity, amount);
    }

    /** How many outbox rows of one type the tenant produced, counted through its own scope. */
    long outboxRows(Session session, String type) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(
                dev.ngb.customer_jooq.Tables.OUTBOX, dev.ngb.customer_jooq.Tables.OUTBOX.TYPE.eq(type))));
    }

    /** The payload of the newest outbox row of one type, or null when the tenant produced none. */
    String outboxPayload(Session session, String type) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            JSONB payload = dsl.select(dev.ngb.customer_jooq.Tables.OUTBOX.PAYLOAD)
                    .from(dev.ngb.customer_jooq.Tables.OUTBOX)
                    .where(dev.ngb.customer_jooq.Tables.OUTBOX.TYPE.eq(type))
                    .orderBy(dev.ngb.customer_jooq.Tables.OUTBOX.CREATED_AT.desc())
                    .limit(1)
                    .fetchOne(dev.ngb.customer_jooq.Tables.OUTBOX.PAYLOAD);
            return payload == null ? null : payload.data();
        }));
    }

    long orderRows(Session session) {
        return rowsOf(session, ORDERS);
    }

    long orderLineRows(Session session) {
        return rowsOf(session, ORDER_LINES);
    }

    long returnRows(Session session) {
        return rowsOf(session, ORDER_RETURNS);
    }
}
