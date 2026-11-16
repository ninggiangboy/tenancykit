package dev.ngb.crm.customers.adapter.field.web;

import static dev.ngb.crm_jooq.Tables.CUSTOMERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.support.CrmApiTest;
import org.junit.jupiter.api.Test;

/**
 * The Tenant's custom fields: the definitions it creates and the values its customers are written with.
 *
 * <p>Four cases decide the design and are each proved here: a type is immutable because the stored values were written
 * under it, an archived definition is still read and takes nothing new, a value under a key the tenant never defined is
 * refused rather than stored, and one customer's values cannot grow without bound.
 */
class CustomFieldIntegrationTest extends CrmApiTest {

    private Session session() {
        return tenant(
                CrmPermissions.CUSTOMER_MANAGE, CrmPermissions.CUSTOMER_READ, CrmPermissions.FIELD_MANAGE);
    }

    @Test
    void aFieldIsDefinedListedAndWrittenOnACustomer() throws Exception {
        Session session = session();

        postAs(session, "/api/v1/custom-fields", """
                {"key":"size","label":"Size","type":"CHOICE","choices":["S","M","L"]}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("size"))
                .andExpect(jsonPath("$.label").value("Size"))
                .andExpect(jsonPath("$.type").value("CHOICE"))
                .andExpect(jsonPath("$.choices.length()").value(3))
                .andExpect(jsonPath("$.archived").value(false));

        getAs(session, "/api/v1/custom-fields")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("size"));

        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"size\":\"M\"}}", "cust-field")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customFields.size").value("M"));

        getAs(session, "/api/v1/customers/{externalRef}", "cust-field")
                .andExpect(jsonPath("$.customFields.size").value("M"));
    }

    @Test
    void theShapeOfAFieldIsEnforced() throws Exception {
        Session session = session();

        postAs(session, "/api/v1/custom-fields", """
                {"key":"9lives","label":"Lives","type":"TEXT"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postAs(session, "/api/v1/custom-fields", """
                {"key":"empty_choice","label":"Empty","type":"CHOICE","choices":[]}
                """)
                .andExpect(status().is(422));
        postAs(session, "/api/v1/custom-fields", """
                {"key":"text_with_choices","label":"Text","type":"TEXT","choices":["a"]}
                """)
                .andExpect(status().is(422));
        postAs(session, "/api/v1/custom-fields", """
                {"key":"no_label","label":"   ","type":"TEXT"}
                """)
                .andExpect(status().is(422));

        assertThat(rowsOf(session, dev.ngb.crm_jooq.Tables.FIELD_DEFINITIONS)).isZero();
    }

    @Test
    void aKeyThatIsTakenIsRefusedWith409() throws Exception {
        Session session = session();
        define(session, "size", "TEXT");

        postAs(session, "/api/v1/custom-fields", """
                {"key":"size","label":"Another size","type":"NUMBER"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIELD_KEY_EXISTS"));
    }

    @Test
    void aTypeChangeIsRefusedAndTheStoredTypeStays() throws Exception {
        Session session = session();
        define(session, "size", "TEXT");

        patchAs(session, "/api/v1/custom-fields/{key}", "{\"type\":\"NUMBER\"}", null, "size")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIELD_TYPE_IMMUTABLE"));

        getAs(session, "/api/v1/custom-fields")
                .andExpect(jsonPath("$.data[0].key").value("size"))
                .andExpect(jsonPath("$.data[0].type").value("TEXT"));
        patchAs(session, "/api/v1/custom-fields/{key}", "{\"label\":\"Size of the shirt\"}", null, "size")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Size of the shirt"))
                .andExpect(jsonPath("$.type").value("TEXT"));
    }

    @Test
    void anArchivedFieldIsStillReadableButTakesNoNewValue() throws Exception {
        Session session = session();
        define(session, "nickname", "TEXT");
        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"nickname\":\"Ada\"}}", "cust-arch")
                .andExpect(status().isCreated());

        patchAs(session, "/api/v1/custom-fields/{key}", "{\"archived\":true}", null, "nickname")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        getAs(session, "/api/v1/customers/{externalRef}", "cust-arch")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customFields.nickname").value("Ada"));

        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"nickname\":\"Grace\"}}", "cust-arch")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        patchAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"nickname\":\"Grace\"}}", "\"1\"",
                        "cust-arch")
                .andExpect(status().is(422));

        getAs(session, "/api/v1/customers/{externalRef}", "cust-arch")
                .andExpect(jsonPath("$.customFields.nickname").value("Ada"));
    }

    @Test
    void aKeyTheTenantNeverDefinedIsRefusedOnEveryWritePath() throws Exception {
        Session session = session();
        define(session, "known", "TEXT");

        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"unknown\":\"x\"}}", "cust-unknown")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowsOf(session, CUSTOMERS)).isZero();

        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"known\":\"x\"}}", "cust-two")
                .andExpect(status().isCreated());
        patchAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"unknown\":\"x\"}}", "\"1\"",
                        "cust-two")
                .andExpect(status().is(422));

        postAs(session, "/api/v1/customers/batch", """
                {"items":[{"externalRef":"cust-batch","customFields":{"unknown":"x"}}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.results[0].code").value("VALIDATION_FAILED"));
    }

    @Test
    void aValueOfTheWrongTypeIsRefused() throws Exception {
        Session session = session();
        define(session, "amount", "NUMBER");
        define(session, "born_on", "DATE");
        define(session, "member", "BOOLEAN");
        postAs(session, "/api/v1/custom-fields", """
                {"key":"size","label":"Size","type":"CHOICE","choices":["S","M"]}
                """).andExpect(status().isCreated());

        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"amount\":\"lots\"}}", "cust-num")
                .andExpect(status().is(422));
        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"born_on\":\"31/12/2026\"}}", "cust-date")
                .andExpect(status().is(422));
        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"member\":\"yes\"}}", "cust-bool")
                .andExpect(status().is(422));
        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"size\":\"XXL\"}}", "cust-choice")
                .andExpect(status().is(422));

        putAs(
                        session,
                        "/api/v1/customers/{externalRef}",
                        "{\"customFields\":{\"amount\":12.5,\"born_on\":\"2026-12-31\",\"member\":true}}",
                        "cust-typed")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customFields.born_on").value("2026-12-31"))
                .andExpect(jsonPath("$.customFields.member").value(true));
    }

    @Test
    void aNullValueClearsTheAnswer() throws Exception {
        Session session = session();
        define(session, "nickname", "TEXT");
        putAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"nickname\":\"Ada\"}}", "cust-clear")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customFields.nickname").value("Ada"));

        patchAs(session, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"nickname\":null}}", "\"1\"",
                        "cust-clear")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customFields.nickname").doesNotExist());

        getAs(session, "/api/v1/customers/{externalRef}", "cust-clear")
                .andExpect(jsonPath("$.customFields.nickname").doesNotExist());
    }

    @Test
    void theSerializedValuesOfOneCustomerAreCapped() throws Exception {
        Session session = session();
        for (int i = 1; i <= 9; i++) {
            define(session, "text_" + i, "TEXT");
        }
        String value = "x".repeat(2000);

        putAs(session, "/api/v1/customers/{externalRef}", bodyOfFields(9, value), "cust-too-big")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowsOf(session, CUSTOMERS)).isZero();

        putAs(session, "/api/v1/customers/{externalRef}", bodyOfFields(7, value), "cust-under-cap")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customFields.text_1").value(value));
    }

    @Test
    void aTenantHoldsAtMostFiftyActiveDefinitions() throws Exception {
        Session session = session();
        for (int i = 1; i <= 50; i++) {
            define(session, "field_" + i, "TEXT");
        }

        postAs(session, "/api/v1/custom-fields", """
                {"key":"one_too_many","label":"One too many","type":"TEXT"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        patchAs(session, "/api/v1/custom-fields/{key}", "{\"archived\":true}", null, "field_1")
                .andExpect(status().isOk());
        postAs(session, "/api/v1/custom-fields", """
                {"key":"after_archiving","label":"Fits now","type":"TEXT"}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    void choicesAreAddedToAChoiceButNeverRemoved() throws Exception {
        Session session = session();
        postAs(session, "/api/v1/custom-fields", """
                {"key":"size","label":"Size","type":"CHOICE","choices":["S","M"]}
                """).andExpect(status().isCreated());

        patchAs(session, "/api/v1/custom-fields/{key}", "{\"choices\":[\"L\"]}", null, "size")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices.length()").value(3));
        patchAs(session, "/api/v1/custom-fields/{key}", "{\"choices\":[\"S\"]}", null, "size")
                .andExpect(status().is(422));
    }

    @Test
    void anUnknownFieldIs404() throws Exception {
        Session session = session();

        patchAs(session, "/api/v1/custom-fields/{key}", "{\"label\":\"Nowhere\"}", null, "nowhere")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOM_FIELD_NOT_FOUND"));
    }

    @Test
    void aCredentialWithoutTheFieldPermissionCannotDefineOne() throws Exception {
        Session session = session();
        String customerOnly = credentialOf(session, "Customer only", CrmPermissions.CUSTOMER_MANAGE);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/custom-fields")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"size\",\"label\":\"Size\",\"type\":\"TEXT\"}")
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + customerOnly))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsFieldsAreInvisible() throws Exception {
        Session tenantA = session();
        Session tenantB = session();
        define(tenantA, "size", "TEXT");

        getAs(tenantB, "/api/v1/custom-fields")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        putAs(tenantB, "/api/v1/customers/{externalRef}", "{\"customFields\":{\"size\":\"M\"}}", "cust-b")
                .andExpect(status().is(422));

        getAs(tenantA, "/api/v1/custom-fields").andExpect(jsonPath("$.data.length()").value(1));
    }

    private void define(Session session, String key, String type) throws Exception {
        postAs(
                        session,
                        "/api/v1/custom-fields",
                        "{\"key\":\"" + key + "\",\"label\":\"" + key + "\",\"type\":\"" + type + "\"}")
                .andExpect(status().isCreated());
    }

    private static String bodyOfFields(int count, String value) {
        StringBuilder fields = new StringBuilder("{\"customFields\":{");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                fields.append(',');
            }
            fields.append("\"text_").append(i).append("\":\"").append(value).append('"');
        }
        return fields.append("}}").toString();
    }
}
