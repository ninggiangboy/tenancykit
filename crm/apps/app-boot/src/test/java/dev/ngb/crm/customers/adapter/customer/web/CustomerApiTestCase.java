package dev.ngb.crm.customers.adapter.customer.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static dev.ngb.crm_jooq.Tables.CUSTOMERS;
import static dev.ngb.crm_jooq.Tables.OUTBOX;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.support.CrmIntegrationTest;
import dev.ngb.crm.support.ReplicaFixtures;
import dev.ngb.crm.support.TestTokenFactory;
import dev.ngb.crm.support.TokensConfig;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The customer API tests' common ground: the real application behind MockMvc, a Tenant with a Tenant System
 * credential that holds the two customer codes, and the request builders the tests send.
 *
 * <p>Every test uses a fresh random tenant, so no test has to clean up after another and a customer stored by one is
 * invisible to the next — which is the isolation the tests are about.
 */
@AutoConfigureMockMvc
@Import(TokensConfig.class)
abstract class CustomerApiTestCase extends CrmIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReplicaFixtures replica;

    @Autowired
    DSLContext dsl;

    @Autowired
    UnitOfWork unitOfWork;

    /** A tenant, its hostname and a Tenant System token holding both customer permissions. */
    record Session(TenantId tenant, String host, String token) {}

    Session tenant() {
        ReplicaFixtures.TenantSeed seed = replica.tenant();
        UUID system = replica.tenantSystem(seed.id().value(), "Tenant POS").value();
        String token = TestTokenFactory.tenantSystemToken(
                system,
                seed.id().value(),
                CrmPermissions.CUSTOMER_MANAGE,
                CrmPermissions.CUSTOMER_READ);
        return new Session(seed.id(), seed.host(), token);
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
        return inTenant(session.tenant(), () -> unitOfWork.transactional(
                () -> (long) dsl.fetchCount(CUSTOMERS)));
    }
}
