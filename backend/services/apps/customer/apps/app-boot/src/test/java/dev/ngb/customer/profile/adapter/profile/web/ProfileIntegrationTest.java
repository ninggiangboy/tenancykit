package dev.ngb.customer.profile.adapter.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.customer.activity.application.activity.command.RecordActivityUseCase;
import dev.ngb.customer.orders.application.order.command.RecordOrderUseCase;
import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The 360 profile: one customer read back as one document, with the slices the request asks for and no more.
 *
 * <p>The cases that decide the design are here: every slice is bounded by its own parameter, the consents are the
 * current state of each topic rather than the newest record only, and a customer of another tenant simply is not
 * there.
 */
class ProfileIntegrationTest extends CustomerApiTest {

    @Autowired
    RecordOrderUseCase recordOrder;

    @Autowired
    RecordActivityUseCase recordActivity;

    /** A tenant whose credential holds every code these cases need. */
    private Session tenant() {
        return tenant(
                CustomerPermissions.CUSTOMER_MANAGE,
                CustomerPermissions.CUSTOMER_READ,
                CustomerPermissions.FIELD_MANAGE,
                CustomerPermissions.ORDER_RECORD,
                CustomerPermissions.ORDER_READ,
                CustomerPermissions.ACTIVITY_RECORD,
                CustomerPermissions.ACTIVITY_READ);
    }

    @Test
    void theProfileCarriesTheCustomerItsFieldValuesItsTagsItsConsentsItsOrdersAndItsActivities() throws Exception {
        Session session = tenant();
        defineTextField(session, "colour");
        defineConsentTopic(session, "marketing");
        putCustomer(session, "cust-profile", """
                {"displayName":"Ada Lovelace","phone":"+44 20 7946 0958","email":"ada@example.com",
                 "customFields":{"colour":"green"},"tags":["vip","london"]}
                """).andExpect(status().isCreated());
        recordConsent(session, "cust-profile", "marketing", "GRANTED", "2026-11-10T09:00:00Z");
        order(session, "cust-profile", "order-1", "2026-11-16T09:00:00Z");
        activity(session, "cust-profile", "pos.receipt-printed", "2026-11-16T09:30:00Z", Map.of("receipt", "R-1"));

        profile(session, "cust-profile")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.externalRef").value("cust-profile"))
                .andExpect(jsonPath("$.customer.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.customer.email").value("ada@example.com"))
                .andExpect(jsonPath("$.customFields.colour").value("green"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("london"))
                .andExpect(jsonPath("$.consents.length()").value(1))
                .andExpect(jsonPath("$.consents[0].topicKey").value("marketing"))
                .andExpect(jsonPath("$.consents[0].status").value("GRANTED"))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderRef").value("order-1"))
                .andExpect(jsonPath("$.orders[0].externalRef").value("cust-profile"))
                .andExpect(jsonPath("$.orders[0].lines.length()").value(1))
                .andExpect(jsonPath("$.orders[0].lines[0].sku").value("SKU-1"))
                .andExpect(jsonPath("$.activities.length()").value(1))
                .andExpect(jsonPath("$.activities[0].type").value("pos.receipt-printed"))
                .andExpect(jsonPath("$.activities[0].payload.receipt").value("R-1"));
    }

    @Test
    void theConsentsAreTheCurrentStateOfEveryTopicNotJustTheNewestRecord() throws Exception {
        Session session = tenant();
        defineConsentTopic(session, "marketing");
        defineConsentTopic(session, "profiling");
        putCustomer(session, "cust-consents", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());
        recordConsent(session, "cust-consents", "marketing", "GRANTED", "2026-11-01T09:00:00Z");
        recordConsent(session, "cust-consents", "marketing", "REVOKED", "2026-11-05T09:00:00Z");
        recordConsent(session, "cust-consents", "profiling", "GRANTED", "2026-11-02T09:00:00Z");

        profile(session, "cust-consents")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents.length()").value(2))
                .andExpect(jsonPath("$.consents[0].topicKey").value("marketing"))
                .andExpect(jsonPath("$.consents[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.consents[1].topicKey").value("profiling"))
                .andExpect(jsonPath("$.consents[1].status").value("GRANTED"));
    }

    @Test
    void everySliceIsBoundedByItsOwnParameter() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-bounded", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());
        for (int i = 1; i <= 5; i++) {
            order(session, "cust-bounded", "order-" + i, "2026-11-1%dT09:00:00Z".formatted(i));
            activity(session, "cust-bounded", "app.opened", "2026-11-1%dT10:00:00Z".formatted(i), Map.of("n", i));
        }

        profile(session, "cust-bounded", "orders", "2", "activities", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].orderRef").value("order-5"))
                .andExpect(jsonPath("$.orders[1].orderRef").value("order-4"))
                .andExpect(jsonPath("$.activities.length()").value(3))
                .andExpect(jsonPath("$.activities[0].payload.n").value(5))
                .andExpect(jsonPath("$.activities[2].payload.n").value(3));

        profile(session, "cust-bounded", "orders", "0", "activities", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0))
                .andExpect(jsonPath("$.activities.length()").value(0))
                .andExpect(jsonPath("$.customer.externalRef").value("cust-bounded"));
    }

    @Test
    void aSliceSizeOutsideItsBoundIsRefused() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-limits", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());

        profile(session, "cust-limits", "orders", "51").andExpect(status().isBadRequest());
        profile(session, "cust-limits", "activities", "101").andExpect(status().isBadRequest());
        profile(session, "cust-limits", "orders", "-1").andExpect(status().isBadRequest());
    }

    @Test
    void theProfileOfACustomerThisTenantDoesNotHaveIs404() throws Exception {
        Session session = tenant();

        profile(session, "cust-nowhere").andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsCustomerHasNoProfileHere() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-mine", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());

        Session other = tenant();
        profile(other, "cust-mine").andExpect(status().isNotFound());
    }

    @Test
    void theProfileNeedsTheCustomerReadCode() throws Exception {
        Session session = tenant(CustomerPermissions.ACTIVITY_READ);
        profile(session, "cust-any").andExpect(status().isForbidden());
    }

    private ResultActions profile(Session session, String externalRef, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/customers/{externalRef}/profile", externalRef);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return send(request, session, null, null);
    }

    private ResultActions putCustomer(Session session, String externalRef, String body) throws Exception {
        return send(put("/api/v1/customers/{externalRef}", externalRef), session, body, null);
    }

    private void defineTextField(Session session, String key) throws Exception {
        send(post("/api/v1/custom-fields"), session,
                        "{\"key\":\"%s\",\"label\":\"Colour\",\"type\":\"TEXT\"}".formatted(key), null)
                .andExpect(status().isCreated());
    }

    private void defineConsentTopic(Session session, String key) throws Exception {
        send(post("/api/v1/consent-topics"), session,
                        "{\"key\":\"%s\",\"label\":\"Marketing\"}".formatted(key), null)
                .andExpect(status().isCreated());
    }

    private void recordConsent(
            Session session, String externalRef, String topic, String state, String occurredAt) throws Exception {
        send(post("/api/v1/customers/{externalRef}/consents", externalRef), session, """
                {"topicKey":"%s","status":"%s","occurredAt":"%s"}
                """.formatted(topic, state, occurredAt), null)
                .andExpect(status().isCreated());
    }

    private void order(Session session, String externalRef, String orderRef, String occurredAt) {
        inTenant(session.tenant(), () -> recordOrder.execute(new RecordOrderUseCase.Command(
                orderRef,
                externalRef,
                "EUR",
                Instant.parse(occurredAt),
                "S-1",
                "pos",
                1999L,
                false,
                List.of(new RecordOrderUseCase.Line(
                        "SKU-1", "Coffee", "grocery", new BigDecimal("2"), 1999L, true)))));
    }

    private void activity(Session session, String externalRef, String type, String occurredAt, Map<String, Object> payload) {
        inTenant(session.tenant(), () -> recordActivity.execute(new RecordActivityUseCase.Command(
                externalRef, type, Instant.parse(occurredAt), "app", payload, null)));
    }

    /** Guards against a silent no-op: every case that seeds a slice relies on these bean calls landing. */
    @Test
    void theSeededRowsAreStoredWhereTheProfileReadsThem() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-seeded", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());
        order(session, "cust-seeded", "order-seeded", "2026-11-16T09:00:00Z");
        activity(session, "cust-seeded", "app.opened", "2026-11-16T09:00:00Z", Map.of("n", 1));

        assertThat(inTenant(session.tenant(), () -> unitOfWork.transactional(
                        () -> dsl.fetchCount(dev.ngb.customer_jooq.Tables.ORDERS))))
                .isEqualTo(1);
        assertThat(inTenant(session.tenant(), () -> unitOfWork.transactional(
                        () -> dsl.fetchCount(dev.ngb.customer_jooq.Tables.ACTIVITIES))))
                .isEqualTo(1);
    }
}
