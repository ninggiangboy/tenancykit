package dev.ngb.booking.webhook.adapter.endpoint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registering and managing webhook endpoints: the secret is shown once, a change is conditional, disabling keeps the
 * delivery history, and rotating replaces the signing key.
 */
class EndpointApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theSecretIsShownOnceAndAChangeNeedsTheTag() throws Exception {
        Session session = tenant(BookingPermissions.WEBHOOK_READ, BookingPermissions.WEBHOOK_MANAGE);

        String created = postAs(session, "/system/v1/webhook-endpoints", """
                {"url":"https://example.com/hooks/booking","eventTypes":["booking.held","series.created"]}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpoint.status").value("ACTIVE"))
                .andExpect(jsonPath("$.endpoint.version").value(1))
                .andExpect(jsonPath("$.secret").isString())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        var node = JSON.readTree(created);
        String endpoint = node.get("endpoint").get("id").asString();
        String secret = node.get("secret").asString();
        String keyId = node.get("endpoint").get("keyId").asString();
        assertThat(secret).startsWith("whsec_");

        getAs(session, "/system/v1/webhook-endpoints")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(endpoint))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist());

        getAs(session, "/system/v1/webhook-endpoints/{endpointId}", endpoint)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.keyId").value(keyId));

        patchAs(session, "/system/v1/webhook-endpoints/{endpointId}", "{\"status\":\"DISABLED\"}", null, endpoint)
                .andExpect(status().is(428));

        patchAs(session, "/system/v1/webhook-endpoints/{endpointId}", "{\"status\":\"DISABLED\"}", "\"1\"", endpoint)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.version").value(2));

        String rotated = postAs(session, "/system/v1/webhook-endpoints/{endpointId}/rotate-secret", null, endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isString())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(rotated).get("secret").asString())
                .as("a rotation never reuses the old secret")
                .isNotEqualTo(secret);
        assertThat(JSON.readTree(rotated).get("endpoint").get("keyId").asString())
                .isNotEqualTo(keyId);
    }

    @Test
    void deletingAnEndpointLeavesItsDeliveriesReadable() throws Exception {
        Session session = tenant(BookingPermissions.WEBHOOK_READ, BookingPermissions.WEBHOOK_MANAGE);
        String created = postAs(session, "/system/v1/webhook-endpoints", """
                {"url":"https://example.com/hooks/booking","eventTypes":["booking.held"]}
                """)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String endpoint = JSON.readTree(created).get("endpoint").get("id").asString();

        deleteAs(session, "/system/v1/webhook-endpoints/{endpointId}", "\"1\"", endpoint)
                .andExpect(status().isNoContent());

        getAs(session, "/system/v1/webhook-endpoints")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        getAs(session, "/system/v1/webhook-deliveries")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
