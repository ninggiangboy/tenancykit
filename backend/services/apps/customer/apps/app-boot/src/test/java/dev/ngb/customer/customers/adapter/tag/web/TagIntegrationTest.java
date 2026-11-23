package dev.ngb.customer.customers.adapter.tag.web;

import static dev.ngb.customer_jooq.Tables.CUSTOMER_TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.customer.shared.CustomerPermissions;
import dev.ngb.customer.support.CustomerApiTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tags on a customer, and the listing of the tags a tenant uses.
 *
 * <p>A {@code PUT} states the customer's whole representation and therefore replaces the set; a {@code PATCH} adds and
 * removes without replacing it. A customer carries a bounded number of tags, and the same tag on two customers is one
 * tag with two customers behind it.
 */
class TagIntegrationTest extends CustomerApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Session session() {
        return tenant(CustomerPermissions.CUSTOMER_MANAGE, CustomerPermissions.CUSTOMER_READ);
    }

    @Test
    void aPutReplacesTheSetAndAPatchAddsAndRemoves() throws Exception {
        Session session = session();
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"vip\",\"newsletter\"]}", "cust-tags")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("newsletter"))
                .andExpect(jsonPath("$.tags[1]").value("vip"));

        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"vip\"]}", "cust-tags")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("vip"));

        patchAs(session, "/system/v1/customers/{externalRef}", "{\"addTags\":[\"wholesale\"]}", "\"2\"", "cust-tags")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2));
        patchAs(session, "/system/v1/customers/{externalRef}", "{\"removeTags\":[\"vip\"]}", "\"3\"", "cust-tags")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("wholesale"));

        getAs(session, "/system/v1/customers/{externalRef}", "cust-tags")
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("wholesale"));
    }

    @Test
    void addingATagTheCustomerCarriesOrRemovingOneItDoesNotIsNoChange() throws Exception {
        Session session = session();
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"vip\"]}", "cust-noop")
                .andExpect(status().isCreated());

        patchAs(session, "/system/v1/customers/{externalRef}", "{\"addTags\":[\"vip\"]}", "\"1\"", "cust-noop")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1));
        patchAs(session, "/system/v1/customers/{externalRef}", "{\"removeTags\":[\"absent\"]}", "\"1\"", "cust-noop")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1));
    }

    @Test
    void aCustomerCannotHoldMoreThanFiftyTags() throws Exception {
        Session session = session();

        putAs(session, "/system/v1/customers/{externalRef}", bodyOfTags(50), "cust-fifty")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.length()").value(50));
        putAs(session, "/system/v1/customers/{externalRef}", bodyOfTags(51), "cust-fifty-one")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowsOf(session, CUSTOMER_TAGS)).isEqualTo(50);

        patchAs(session, "/system/v1/customers/{externalRef}", "{\"addTags\":[\"one_more\"]}", "\"1\"", "cust-fifty")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aTagIsStoredLowerCasedAndRefusesAnotherShape() throws Exception {
        Session session = session();
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"VIP\",\" vip \"]}", "cust-case")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("vip"));

        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"not a tag!\"]}", "cust-bad-tag")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"" + "x".repeat(51) + "\"]}", "cust-long-tag")
                .andExpect(status().is(422));
    }

    @Test
    void theListingCountsTheCustomersBehindEachTagAndPagesByCursor() throws Exception {
        Session session = session();
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"vip\",\"newsletter\"]}", "cust-one")
                .andExpect(status().isCreated());
        putAs(session, "/system/v1/customers/{externalRef}", "{\"tags\":[\"vip\"]}", "cust-two")
                .andExpect(status().isCreated());

        getAs(session, "/system/v1/tags")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].tag").value("newsletter"))
                .andExpect(jsonPath("$.data[0].customerCount").value(1))
                .andExpect(jsonPath("$.data[1].tag").value("vip"))
                .andExpect(jsonPath("$.data[1].customerCount").value(2));

        String first = getAs(session, "/system/v1/tags?limit=1&page_info=cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tag").value("newsletter"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cursor = cursor(first);
        assertThat(cursor).isNotBlank();

        getAs(session, "/system/v1/tags?limit=1&cursor=" + cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tag").value("vip"));
    }

    @Test
    void anotherTenantsTagsAreInvisible() throws Exception {
        Session tenantA = session();
        Session tenantB = session();
        putAs(tenantA, "/system/v1/customers/{externalRef}", "{\"tags\":[\"secret\"]}", "cust-a")
                .andExpect(status().isCreated());

        getAs(tenantB, "/system/v1/tags")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        getAs(tenantA, "/system/v1/tags")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tag").value("secret"));
    }

    @Test
    void aCredentialWithoutTheReadPermissionCannotListTags() throws Exception {
        Session session = session();
        String manageOnly = credentialOf(session, "Manage only", CustomerPermissions.CUSTOMER_MANAGE);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/system/v1/tags")
                        .header("Host", session.host())
                        .header("Authorization", "Bearer " + manageOnly))
                .andExpect(status().isForbidden());
    }

    private static String bodyOfTags(int count) {
        List<String> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add("\"tag_" + i + "\"");
        }
        return "{\"tags\":[" + String.join(",", tags) + "]}";
    }

    private static String cursor(String body) {
        JsonNode page = JSON.readTree(body).get("page");
        return page == null || page.get("next_cursor") == null ? null : page.get("next_cursor").asString();
    }
}
