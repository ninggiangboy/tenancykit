package dev.ngb.customer.customers.adapter.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.TestTokenFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Tenant's customers are invisible to another's, however the caller reaches them.
 *
 * <p>The reference is the Tenant's own key, so two Tenants may use the same one for two different people; every call
 * is narrowed to the caller's tenant by the token, by row-level security and by the path the endpoint walks.
 */
class CustomerIsolationTest extends CustomerApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void twoTenantsMayUseTheSameReference() throws Exception {
        Session tenantA = tenant();
        Session tenantB = tenant();

        String idA = id(putCustomer(tenantA, "shared-ref", "{\"displayName\":\"Ada of A\"}")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        String idB = id(putCustomer(tenantB, "shared-ref", "{\"displayName\":\"Bob of B\"}")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(idA).isNotEqualTo(idB);
        getCustomer(tenantA, "shared-ref").andExpect(jsonPath("$.displayName").value("Ada of A"));
        getCustomer(tenantB, "shared-ref").andExpect(jsonPath("$.displayName").value("Bob of B"));
        assertThat(outboxRows(tenantA, "customer.created")).isEqualTo(1);
        assertThat(outboxRows(tenantB, "customer.created")).isEqualTo(1);
    }

    @Test
    void anotherTenantsCustomerIsNotFound() throws Exception {
        Session tenantA = tenant();
        Session tenantB = tenant();
        putCustomer(tenantA, "cust-secret", "{\"displayName\":\"Ada of A\"}").andExpect(status().isCreated());

        getCustomer(tenantB, "cust-secret").andExpect(status().isNotFound());
        patchCustomer(tenantB, "cust-secret", "\"1\"", "{\"displayName\":\"Stolen\"}")
                .andExpect(status().isNotFound());
        listCustomers(tenantB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        getCustomer(tenantA, "cust-secret").andExpect(jsonPath("$.displayName").value("Ada of A"));
        assertThat(customerRows(tenantB)).isZero();
    }

    @Test
    void aCredentialWithoutThePermissionCannotWrite() throws Exception {
        Session session = tenant();
        UUID system = replica.tenantSystem(session.tenant().value(), "Rival POS").value();
        String readOnly = TestTokenFactory.tenantSystemToken(
                system, session.tenant().value(), CustomerPermissions.CUSTOMER_READ);

        putCustomer(session, "cust-read-only", "{\"displayName\":\"Ada\"}", readOnly)
                .andExpect(status().isForbidden());
        mvc.perform(get("/system/v1/customers/cust-read-only")
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithoutATokenIsRefused() throws Exception {
        Session session = tenant();

        mvc.perform(get("/system/v1/customers").header("Host", session.host()))
                .andExpect(status().isUnauthorized());
    }

    private static String id(String body) {
        return JSON.readTree(body).get("id").asString();
    }
}
