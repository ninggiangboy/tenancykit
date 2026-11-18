package dev.ngb.customer.customers.adapter.customer.web;

import static dev.ngb.customer_jooq.Tables.CUSTOMERS;
import static dev.ngb.customer_jooq.Tables.OUTBOX;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import dev.ngb.customer.support.ReplicaFixtures;
import dev.ngb.customer.support.TestTokenFactory;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The customer API tests' common ground: a Tenant with a Tenant System credential that holds the two customer codes,
 * and the request builders the tests send.
 */
abstract class CustomerApiTestCase extends CustomerApiTest {

    /** A tenant whose credential holds both customer codes. */
    Session tenant() {
        return tenant(CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_READ);
    }

    /** A second credential of the same tenant, holding only the codes it names. */
    String readOnlyCredential(Session session) {
        UUID system = replica.tenantSystem(session.tenant().value(), "Rival POS").value();
        return TestTokenFactory.tenantSystemToken(
                system, session.tenant().value(), CustomerPermissions.CUSTOMER_READ);
    }

    ResultActions putCustomer(Session session, String externalRef, String body) throws Exception {
        return mvc.perform(put("/api/v1/customers/{externalRef}", externalRef)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token()));
    }

    ResultActions putCustomer(Session session, String externalRef, String body, String token) throws Exception {
        return mvc.perform(put("/api/v1/customers/{externalRef}", externalRef)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + token));
    }

    ResultActions patchCustomer(Session session, String externalRef, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = patch("/api/v1/customers/{externalRef}", externalRef)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token());
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    ResultActions getCustomer(Session session, String externalRef) throws Exception {
        return mvc.perform(get("/api/v1/customers/{externalRef}", externalRef)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token()));
    }

    ResultActions listCustomers(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/customers")
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    ResultActions batchCustomers(Session session, String body) throws Exception {
        return mvc.perform(post("/api/v1/customers/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Host", session.host())
                .header("Authorization", "Bearer " + session.token()));
    }

    /** How many outbox rows the tenant produced, counted as the app role sees them, which is through its own tenant. */
    long outboxRows(Session session, String type) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(
                OUTBOX, OUTBOX.TYPE.eq(type))));
    }

    /** The payload of the tenant's single announcement of {@code type}. */
    String outboxPayload(Session session, String type) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            JSONB payload = dsl.select(OUTBOX.PAYLOAD)
                    .from(OUTBOX)
                    .where(OUTBOX.TYPE.eq(type))
                    .orderBy(OUTBOX.CREATED_AT)
                    .limit(1)
                    .fetchOne(OUTBOX.PAYLOAD);
            return payload == null ? null : payload.data();
        }));
    }

    long customerRows(Session session) {
        return rowsOf(session, CUSTOMERS);
    }
}
