package dev.ngb.loyalty.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.suite.security.SuiteAuthentication;
import dev.ngb.core.tenancy.RequestScope;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP harness: the real MVC stack, the real controllers and the real interceptors, with the tenant bound and an
 * already-resolved Tenant System actor carrying the permissions a call needs.
 *
 * <p>The token chain itself — a minted suite token, the host, the entitlement and the actor resolver — is proved once by
 * the access test; here the interesting question is what the program endpoints answer, so the actor is placed in the
 * security context directly instead of minting a token for every case.
 */
@AutoConfigureMockMvc(addFilters = false)
public abstract class LoyaltyApiTest extends LoyaltyIntegrationTest {

    @Autowired protected MockMvc mvc;

    /** One answer: the status, the body, and the ETag that came with it. */
    public record ApiResponse(int status, String body, String etag) {

        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        public JsonNode json() {
            return MAPPER.readTree(body);
        }

        /** The problem code of an error body, or null when the body carries none. */
        public String code() {
            JsonNode code = json().get("code");
            return code == null ? null : code.asString();
        }
    }

    /** Runs {@code request} as a Tenant System of {@code tenant} holding {@code permissions}. */
    protected ApiResponse call(TenantId tenant, MockHttpServletRequestBuilder request, String... permissions) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> perform(request, tenant, permissions));
    }

    private ApiResponse perform(
            MockHttpServletRequestBuilder request, TenantId tenant, String[] permissions) {
        SecurityContextHolder.getContext()
                .setAuthentication(new SuiteAuthentication(new Actor(
                        ActorType.TENANT_SYSTEM,
                        UUID.randomUUID(),
                        tenant,
                        PermissionScope.TENANT,
                        "pos",
                        Set.of(permissions))));
        try {
            MvcResult result = mvc.perform(request).andReturn();
            return new ApiResponse(
                    result.getResponse().getStatus(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                    result.getResponse().getHeader("ETag"));
        } catch (Exception e) {
            throw new IllegalStateException("the request could not be performed", e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
