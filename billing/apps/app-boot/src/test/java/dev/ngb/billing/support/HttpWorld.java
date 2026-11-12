package dev.ngb.billing.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds real tenants, users and Tenant Systems in the replica and calls the API over MockMvc with real signed
 * tokens. Billing owns none of that data any more: the rows are seeded as Platform would publish them, and the
 * tokens are minted with the test key pair ({@link TestTokenFactory}).
 */
public final class HttpWorld {

    /** Every Billing code a tenant administrator holds, for the seeded admin user. */
    public static final List<String> ADMIN_PERMISSIONS = List.of(
            "billing:customer:read", "billing:customer:write",
            "billing:billing-session:create", "billing:portal-settings:manage",
            "billing:wallet:read", "billing:wallet:manage", "billing:reservation:manage", "billing:charge:create",
            "billing:ledger:reverse", "billing:pricing:read", "billing:pricing:manage",
            "billing:usage:record", "billing:usage:read",
            "billing:stripe:read", "billing:stripe:manage",
            "billing:payment:read", "billing:payment:create", "billing:refund:create",
            "billing:invoice:read", "billing:invoice:create", "billing:invoice:manage",
            "billing:subscription:read", "billing:subscription:manage",
            "billing:webhook:read", "billing:webhook:manage");

    /** A seeded tenant: its id, code, hostname and the token of an administrator who holds every Billing code. */
    public record Account(String tenantId, String code, String host, String adminToken) {

        public TenantId id() {
            return new TenantId(UUID.fromString(tenantId));
        }
    }

    /** A Tenant System with a token for the given permission codes. */
    public record System(String id, String token, TenantId tenantId) {

        public UUID uuid() {
            return UUID.fromString(id);
        }

        /** Another token for the same system, with a narrower or different grant. */
        public String tokenFor(String... scopes) {
            return TestTokenFactory.tenantSystemToken(uuid(), tenantId.value(), scopes);
        }
    }

    private final MockMvc mvc;
    private final ReplicaFixtures replica;
    private final ScopeTestSupport scopes;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpWorld(MockMvc mvc, ReplicaFixtures replica, ScopeTestSupport scopes) {
        this.mvc = mvc;
        this.replica = replica;
        this.scopes = scopes;
    }

    /** A fresh active tenant, entitled to Billing, with its own hostname and an administrator token. */
    public Account newTenant() {
        ReplicaFixtures.TenantSeed seed = replica.tenant();
        String adminSubject = "admin-" + UUID.randomUUID();
        replica.user(seed.id().value(), adminSubject, ADMIN_PERMISSIONS.toArray(String[]::new));
        return new Account(
                seed.id().toString(), seed.code(), seed.host(), TestTokenFactory.userToken(adminSubject));
    }

    /** A Tenant System of the tenant, with a Platform token naming only the given codes. */
    public System newSystem(Account account, String... scopes) {
        TenantSystemId id = replica.tenantSystem(account.id().value(), "System " + UUID.randomUUID());
        return new System(
                id.toString(),
                TestTokenFactory.tenantSystemToken(id.value(), account.id().value(), scopes),
                account.id());
    }

    /** A Tenant User of the tenant, with a Keycloak-shaped token for the given codes. */
    public String newUserToken(Account account, String... permissions) {
        String subject = "kc-" + UUID.randomUUID();
        replica.user(account.id().value(), subject, permissions);
        return TestTokenFactory.userToken(subject);
    }

    /** A Tenant User holding exactly the given codes. */
    public String newUserToken(Account account, Set<String> permissionCodes) {
        return newUserToken(account, permissionCodes.toArray(String[]::new));
    }

    public MvcResult call(HttpMethod method, String host, String token, String path, Object payload) {
        try {
            MockHttpServletRequestBuilder builder = request(method, path).header("Host", host);
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (payload != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
            }
            return mvc.perform(builder).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public int status(HttpMethod method, String host, String token, String path, Object payload) {
        return call(method, host, token, path, payload).getResponse().getStatus();
    }

    public JsonNode body(MvcResult result) {
        try {
            int status = result.getResponse().getStatus();
            if (status >= 300) {
                throw new IllegalStateException("unexpected status " + status + ": " + result.getResponse().getContentAsString());
            }
            return json.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tiny map builder that accepts mixed value types without unchecked generics. */
    public static final class Map2 {
        private Map2() {}

        public static Map<String, Object> of(Object... keyValues) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
            return map;
        }
    }

    /** The scope helper, for a test that needs to run work inside a seeded tenant. */
    public ScopeTestSupport scopes() {
        return scopes;
    }

    /** The replica fixture, for a test that needs to seed or remove a row directly. */
    public ReplicaFixtures replica() {
        return replica;
    }
}
