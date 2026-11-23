package dev.ngb.customer.orders.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One Tenant's orders are invisible to another's, however the caller reaches them.
 *
 * <p>The reference is the Tenant's own key, so two Tenants may use the same one for two different orders; every call is
 * narrowed to the caller's tenant by the token, by row-level security and by the customer the order belongs to.
 */
class OrderIsolationTest extends OrderApiTestCase {

    @Test
    void twoTenantsMayUseTheSameOrderReference() throws Exception {
        Session tenantA = tenant();
        Session tenantB = tenant();
        Instant occurredAt = past();

        putOrder(tenantA, "order-shared", order("cust-shared", occurredAt, List.of(line("SKU-A", "1", 100))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRef").value("cust-shared"));
        putOrder(tenantB, "order-shared", order("cust-shared", occurredAt, List.of(line("SKU-B", "2", 200))))
                .andExpect(status().isCreated());

        getOrder(tenantA, "order-shared").andExpect(jsonPath("$.lines[0].sku").value("SKU-A"));
        getOrder(tenantB, "order-shared").andExpect(jsonPath("$.lines[0].sku").value("SKU-B"));
        assertThat(outboxRows(tenantA, "order.recorded")).isEqualTo(1);
        assertThat(outboxRows(tenantB, "order.recorded")).isEqualTo(1);
    }

    @Test
    void anotherTenantsOrderIsNotFound() throws Exception {
        Session tenantA = tenant();
        Session tenantB = tenant();
        putOrder(tenantA, "order-secret", order("cust-secret", past(), List.of(line("SKU-1", "3.000", 900))))
                .andExpect(status().isCreated());

        getOrder(tenantB, "order-secret").andExpect(status().isNotFound());
        putReturn(tenantB, "order-secret", "return-stolen", returnBody(past(), returnedLine(1, "1.000", 300)))
                .andExpect(status().isNotFound());
        assertThat(returnRows(tenantB)).isZero();

        getOrder(tenantA, "order-secret")
                .andExpect(jsonPath("$.orderVersion").value(1))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(0));
    }

    @Test
    void anotherTenantsCustomerOrdersAreNotVisible() throws Exception {
        Session tenantA = tenant();
        Session tenantB = tenant();
        putOrder(tenantA, "order-of-a", order("cust-of-a", past(), List.of(line("SKU-1", "1", 100))))
                .andExpect(status().isCreated());

        listOrders(tenantB, "cust-of-a")
                .andExpect(status().isNotFound());
        listOrders(tenantA, "cust-of-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderRef").value("order-of-a"));
    }

    @Test
    void aRequestWithoutATokenIsRefused() throws Exception {
        Session session = tenant();

        mvc.perform(get("/system/v1/orders/order-anything").header("Host", session.host()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/system/v1/customers/cust-anything/orders").header("Host", session.host()))
                .andExpect(status().isUnauthorized());
    }
}
