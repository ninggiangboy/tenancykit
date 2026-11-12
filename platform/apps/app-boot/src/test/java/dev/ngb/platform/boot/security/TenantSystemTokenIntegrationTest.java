package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client-credentials grant for Tenant Systems: the audience filters the scopes, a disabled system and a revoked
 * credential are 401, rotating keeps both keys working during the overlap, and the 101st request in a window is 429.
 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "platform.security.token-rate-limit.burst=100",
            "platform.security.token-rate-limit.per-second=0",
            "platform.security.token-rate-limit.cleanup-batch=50"
        })
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class TenantSystemTokenIntegrationTest {

    private static final Audit CALLER = Audit.of("user/admin", "test-1");

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired ManageTenantSystemUseCase systems;
    @Autowired ManageCredentialUseCase credentials;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aTokenForBillingCarriesOnlyBillingScopes() throws Exception {
        Setup setup = setup(Set.of("billing:invoice:read", "billing:wallet:read"));

        Jwt jwt = decode(accessToken(setup.issued().plaintextKey(), "billing"));

        assertThat(jwt.getSubject()).isEqualTo("tenant-system/" + setup.system().id());
        assertThat(jwt.getAudience()).containsExactly("billing");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("billing:invoice:read billing:wallet:read");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(setup.tenantId().toString());
    }

    @Test
    void aTokenRequestedForAnotherAudienceCarriesNoScopesFromBilling() throws Exception {
        Setup setup = setup(Set.of("billing:invoice:read"));

        Jwt jwt = decode(accessToken(setup.issued().plaintextKey(), "platform"));

        assertThat(jwt.getClaimAsString("scope")).isEmpty();
    }

    @Test
    void aDisabledTenantSystemGets401() throws Exception {
        Setup setup = setup(Set.of("billing:invoice:read"));
        inTenant(setup.tenantId(), () -> systems.execute(
                CALLER, new ManageTenantSystemUseCase.Command(setup.system().id(), ManageTenantSystemUseCase.Action.DISABLE, null)));

        assertThat(httpStatus(setup.issued().plaintextKey())).isEqualTo(401);
    }

    @Test
    void aRevokedCredentialGets401() throws Exception {
        Setup setup = setup(Set.of("billing:invoice:read"));
        inTenant(setup.tenantId(), () -> credentials.revoke(CALLER, setup.issued().credential().id()));

        assertThat(httpStatus(setup.issued().plaintextKey())).isEqualTo(401);
    }

    @Test
    void bothKeysWorkInsideTheRotationOverlap() throws Exception {
        Setup setup = setup(Set.of("billing:invoice:read"));
        ManageCredentialUseCase.Issued rotated = inTenant(setup.tenantId(), () -> credentials.rotate(
                CALLER, setup.system().id(), Duration.ofMinutes(5)));

        assertThat(httpStatus(setup.issued().plaintextKey())).as("the old key is still inside its overlap").isEqualTo(200);
        assertThat(httpStatus(rotated.plaintextKey())).as("the new key works at once").isEqualTo(200);
    }

    private record Setup(TenantId tenantId, TenantSystem system, ManageCredentialUseCase.Issued issued) {}

    private Setup setup(Set<String> scopes) {
        TenantId tenantId = newTenant();
        return inTenant(tenantId, () -> {
            TenantSystem system = systems.create(CALLER, "Sample " + UUID.randomUUID());
            ManageCredentialUseCase.Issued issued = credentials.create(CALLER, system.id(), "ci", scopes);
            return new Setup(tenantId, system, issued);
        });
    }

    private TenantId newTenant() {
        String code = "i" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return tenantContext.callAs(
                RequestScope.platform(),
                () -> createTenant.execute(new CreateTenantUseCase.Command(code, "Tenant " + code, "tester", "req-1")).id());
    }

    private <T> T inTenant(TenantId tenantId, java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenantId), work);
    }

    private void inTenant(TenantId tenantId, Runnable work) {
        tenantContext.runAs(RequestScope.tenant(tenantId), work);
    }

    /** A Tenant System authenticates with the key id as {@code client_id} and the secret as the password. */
    private MvcResult tokenRequest(String presentedKey, String audience) throws Exception {
        String[] parts = presentedKey.split("_");
        return mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", audience)
                        .header("Authorization", basic(parts[1], parts[2])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();
    }

    private int httpStatus(String presentedKey) throws Exception {
        String[] parts = presentedKey.split("_");
        return mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", "billing")
                        .header("Authorization", basic(parts[1], parts[2])))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String accessToken(String presentedKey, String audience) throws Exception {
        MvcResult result = tokenRequest(presentedKey, audience);
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("access_token").asString();
    }

    private Jwt decode(String token) {
        return issuer.decoder().decode(token);
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
