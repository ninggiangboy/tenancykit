package dev.ngb.customer.customers.adapter.consent.web;

import static dev.ngb.customer_jooq.Tables.CONSENT_EVENTS;
import static dev.ngb.customer_jooq.Tables.CONSENT_TOPICS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Consent topics and the records a customer's agreements are made of.
 *
 * <p>The cases that decide the design are each proved here: a revocation appends and leaves the grant it revokes in the
 * history, a caller's own reference makes a record replayable instead of duplicated, an instant far in the future is
 * refused because it would sort above every honest record, and an archived topic keeps its history and takes nothing
 * new.
 */
class ConsentIntegrationTest extends CustomerApiTest {

    private Session session() {
        return tenant(
                CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_READ, CustomerPermissions.FIELD_MANAGE);
    }

    @Test
    void aRevocationAppendsAndTheGrantItRevokesStaysInTheHistory() throws Exception {
        Session session = session();
        topic(session, "marketing_email");
        customer(session, "cust-consent");
        Instant granted = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        Instant revoked = granted.plusSeconds(60);

        record(session, "cust-consent", "marketing_email", "GRANTED", granted, "ref-grant")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.consentRef").value("ref-grant"));
        record(session, "cust-consent", "marketing_email", "REVOKED", revoked, "ref-revoke")
                .andExpect(status().isCreated());

        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-consent")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.length()").value(1))
                .andExpect(jsonPath("$.current[0].topicKey").value("marketing_email"))
                .andExpect(jsonPath("$.current[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.history[1].status").value("GRANTED"));

        assertThat(rowsOf(session, CONSENT_EVENTS))
                .as("a revocation adds a record and rewrites none")
                .isEqualTo(2);
    }

    @Test
    void twoRecordsWithOneConsentReferenceAreOneRow() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-replay");
        String body = recordBody("profiling", "GRANTED", Instant.now().minusSeconds(30), "ref-same");

        postAs(session, "/api/v1/customers/{externalRef}/consents", body, "cust-replay")
                .andExpect(status().isCreated());
        postAs(session, "/api/v1/customers/{externalRef}/consents", body, "cust-replay")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentRef").value("ref-same"));

        assertThat(rowsOf(session, CONSENT_EVENTS)).as("a replay appends nothing").isEqualTo(1);
        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-replay")
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void theSameReferenceWithAnotherBodyIsAConflict() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-conflict");
        Instant when = Instant.now().minusSeconds(30);

        postAs(
                        session,
                        "/api/v1/customers/{externalRef}/consents",
                        recordBody("profiling", "GRANTED", when, "ref-twice"),
                        "cust-conflict")
                .andExpect(status().isCreated());
        postAs(
                        session,
                        "/api/v1/customers/{externalRef}/consents",
                        recordBody("profiling", "REVOKED", when, "ref-twice"),
                        "cust-conflict")
                .andExpect(status().isConflict());

        assertThat(rowsOf(session, CONSENT_EVENTS)).isEqualTo(1);
    }

    @Test
    void anInstantMoreThanFiveMinutesAheadIsRefused() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-future");

        record(session, "cust-future", "profiling", "GRANTED", Instant.now().plus(10, ChronoUnit.MINUTES), "f-1")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowsOf(session, CONSENT_EVENTS)).isZero();

        record(session, "cust-future", "profiling", "GRANTED", Instant.now().plus(4, ChronoUnit.MINUTES), "f-2")
                .andExpect(status().isCreated());
    }

    @Test
    void aStatusOtherThanGrantedOrRevokedIsRefused() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-status");

        record(session, "cust-status", "profiling", "MAYBE", Instant.now().minusSeconds(5), "s-1")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anUnknownOrArchivedTopicTakesNoRecord() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-topic");
        record(session, "cust-topic", "profiling", "GRANTED", Instant.now().minusSeconds(60), "t-1")
                .andExpect(status().isCreated());

        record(session, "cust-topic", "newsletter", "GRANTED", Instant.now().minusSeconds(30), "t-2")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CONSENT_TOPIC_UNKNOWN"));

        patchAs(session, "/api/v1/consent-topics/{key}", "{\"archived\":true}", null, "profiling")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        record(session, "cust-topic", "profiling", "REVOKED", Instant.now().minusSeconds(10), "t-3")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CONSENT_TOPIC_UNKNOWN"));

        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-topic")
                .andExpect(jsonPath("$.current[0].status").value("GRANTED"))
                .andExpect(jsonPath("$.history.length()").value(1));
        getAs(session, "/api/v1/consent-topics")
                .andExpect(jsonPath("$.data[0].key").value("profiling"))
                .andExpect(jsonPath("$.data[0].archived").value(true));
    }

    @Test
    void everyTopicTheCustomerAnsweredIsCurrentOnItsOwn() throws Exception {
        Session session = session();
        topic(session, "profiling");
        topic(session, "newsletter");
        customer(session, "cust-two-topics");
        Instant when = Instant.now().minusSeconds(60);

        record(session, "cust-two-topics", "profiling", "GRANTED", when, "a-1").andExpect(status().isCreated());
        record(session, "cust-two-topics", "newsletter", "REVOKED", when.plusSeconds(1), "a-2")
                .andExpect(status().isCreated());

        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-two-topics")
                .andExpect(jsonPath("$.current.length()").value(2))
                .andExpect(jsonPath("$.current[0].topicKey").value("newsletter"))
                .andExpect(jsonPath("$.current[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.current[1].topicKey").value("profiling"))
                .andExpect(jsonPath("$.current[1].status").value("GRANTED"))
                .andExpect(jsonPath("$.history.length()").value(2));
    }

    @Test
    void aTopicKeyAndLabelAreEnforcedAndTheKeyIsTakenOnce() throws Exception {
        Session session = session();

        postAs(session, "/api/v1/consent-topics", "{\"key\":\"Not A Key\",\"label\":\"Bad\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postAs(session, "/api/v1/consent-topics", "{\"key\":\"profiling\",\"label\":\"   \"}")
                .andExpect(status().is(422));
        topic(session, "profiling");
        postAs(session, "/api/v1/consent-topics", "{\"key\":\"profiling\",\"label\":\"Again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_TOPIC_KEY_EXISTS"));
        patchAs(session, "/api/v1/consent-topics/{key}", "{\"label\":\"Nowhere\"}", null, "nowhere")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONSENT_TOPIC_NOT_FOUND"));
        assertThat(rowsOf(session, CONSENT_TOPICS)).isEqualTo(1);
    }

    @Test
    void aConsentForACustomerThatDoesNotExistHereIs404() throws Exception {
        Session session = session();
        topic(session, "profiling");

        record(session, "cust-nowhere", "profiling", "GRANTED", Instant.now().minusSeconds(5), "x-1")
                .andExpect(status().isNotFound());
        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-nowhere")
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsTopicsAndConsentsAreInvisible() throws Exception {
        Session tenantA = session();
        Session tenantB = session();
        topic(tenantA, "profiling");
        customer(tenantA, "cust-shared");
        record(tenantA, "cust-shared", "profiling", "GRANTED", Instant.now().minusSeconds(60), "z-1")
                .andExpect(status().isCreated());

        getAs(tenantB, "/api/v1/consent-topics")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        customer(tenantB, "cust-shared");
        record(tenantB, "cust-shared", "profiling", "GRANTED", Instant.now().minusSeconds(30), "z-2")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CONSENT_TOPIC_UNKNOWN"));
        getAs(tenantB, "/api/v1/customers/{externalRef}/consents", "cust-shared")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(0));

        getAs(tenantA, "/api/v1/customers/{externalRef}/consents", "cust-shared")
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void readingAndRecordingConsentNeedTheirOwnCodes() throws Exception {
        Session session = session();
        topic(session, "profiling");
        customer(session, "cust-perm");
        String readOnly = credentialOf(session, "Read only", CustomerPermissions.CUSTOMER_READ);

        mvc.perform(post("/api/v1/customers/{externalRef}/consents", "cust-perm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody("profiling", "GRANTED", Instant.now().minusSeconds(5), "p-1"))
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isForbidden());
        getAs(session, "/api/v1/customers/{externalRef}/consents", "cust-perm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(0));
    }

    private void topic(Session session, String key) throws Exception {
        postAs(
                        session,
                        "/api/v1/consent-topics",
                        "{\"key\":\"" + key + "\",\"label\":\"Topic " + key + "\"}")
                .andExpect(status().isCreated());
    }

    private void customer(Session session, String externalRef) throws Exception {
        putAs(session, "/api/v1/customers/{externalRef}", "{}", externalRef).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions record(
            Session session,
            String externalRef,
            String topicKey,
            String status,
            Instant occurredAt,
            String consentRef)
            throws Exception {
        return postAs(
                session,
                "/api/v1/customers/{externalRef}/consents",
                recordBody(topicKey, status, occurredAt, consentRef),
                externalRef);
    }

    private static String recordBody(String topicKey, String status, Instant occurredAt, String consentRef) {
        return "{\"topicKey\":\"" + topicKey + "\",\"status\":\"" + status + "\",\"occurredAt\":\"" + occurredAt
                + "\",\"source\":\"pos\",\"consentRef\":\"" + consentRef + "\"}";
    }
}
