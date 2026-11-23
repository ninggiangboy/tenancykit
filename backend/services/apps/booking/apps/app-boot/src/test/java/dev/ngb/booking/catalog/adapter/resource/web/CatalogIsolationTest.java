package dev.ngb.booking.catalog.adapter.resource.web;

import static dev.ngb.booking_jooq.Tables.RESOURCES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Tenant's resources are invisible to another's, however the caller reaches them.
 *
 * <p>The code is the Tenant's own key, so two Tenants may use the same one for two different resources; every call is
 * narrowed to the caller's tenant by the token, by row-level security and by the path the endpoint walks.
 */
class CatalogIsolationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void twoTenantsMayUseTheSameResourceCode() throws Exception {
        Session tenantA = reader();
        Session tenantB = reader();

        String idA = create(tenantA, "shared-code");
        String idB = create(tenantB, "shared-code");

        assertThat(idA).isNotEqualTo(idB);
        getAs(tenantA, "/system/v1/resources/{resourceId}", idA).andExpect(jsonPath("$.code").value("shared-code"));
        getAs(tenantB, "/system/v1/resources/{resourceId}", idB).andExpect(jsonPath("$.code").value("shared-code"));
    }

    @Test
    void anotherTenantsResourceIsNotFound() throws Exception {
        Session tenantA = reader();
        Session tenantB = reader();
        String idA = create(tenantA, "tenant-a-only");

        getAs(tenantB, "/system/v1/resources/{resourceId}", idA).andExpect(status().isNotFound());
        patchAs(tenantB, "/system/v1/resources/{resourceId}", "{\"name\":\"Stolen\"}", "\"1\"", idA)
                .andExpect(status().isNotFound());
        deleteAs(tenantB, "/system/v1/resources/{resourceId}", "\"1\"", idA).andExpect(status().isNotFound());

        getAs(tenantB, "/system/v1/resources")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(rowsOf(tenantB, RESOURCES)).isZero();
        getAs(tenantA, "/system/v1/resources/{resourceId}", idA).andExpect(jsonPath("$.code").value("tenant-a-only"));
    }

    @Test
    void aCredentialWithoutTheManagePermissionCannotCreate() throws Exception {
        Session session = reader();
        String readOnly = credentialOf(session, "Read Only", BookingPermissions.RESOURCE_READ);

        send(post("/system/v1/resources"), session, """
                {"code":"room-forbidden","name":"Room","zone":"UTC"}
                """, readOnly, null)
                .andExpect(status().isForbidden());

        assertThat(rowsOf(session, RESOURCES)).isZero();
    }

    @Test
    void aRequestWithoutATokenIsRefusedWith401() throws Exception {
        Session session = reader();

        mvc.perform(get("/system/v1/resources").header("Host", session.host()))
                .andExpect(status().isUnauthorized());
    }

    private Session reader() {
        return tenant(BookingPermissions.RESOURCE_READ, BookingPermissions.RESOURCE_MANAGE);
    }

    private String create(Session session, String code) throws Exception {
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Resource %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("id").asString();
    }
}
