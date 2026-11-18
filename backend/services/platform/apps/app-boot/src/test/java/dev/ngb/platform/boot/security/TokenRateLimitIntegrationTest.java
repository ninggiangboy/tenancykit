package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The token endpoint is rate-limited per credential: the 4th request in a burst of 3 is refused with a Retry-After. */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "platform.security.token-rate-limit.burst=3",
            "platform.security.token-rate-limit.per-second=0"
        })
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class TokenRateLimitIntegrationTest {

    private static final Audit CALLER = Audit.of("user/admin", "test-1");

    @Autowired MockMvc mvc;
    @Autowired TenantContext tenantContext;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired ManageTenantSystemUseCase systems;
    @Autowired ManageCredentialUseCase credentials;

    @Test
    void theRequestPastTheBurstIsToldToSlowDown() throws Exception {
        String key = newCredential();

        for (int i = 0; i < 3; i++) {
            assertThat(status(key)).as("request %s is inside the burst", i + 1).isEqualTo(200);
        }
        MvcResult limited = request(key);

        assertThat(limited.getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("1");
    }

    private String newCredential() {
        String code = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TenantId tenantId = tenantContext.callAs(
                RequestScope.platform(),
                () -> createTenant.execute(new CreateTenantUseCase.Command(code, "Tenant " + code, "tester", "req-1")).id());
        return tenantContext.callAs(RequestScope.tenant(tenantId), () -> {
            TenantSystem system = systems.create(CALLER, "Sample " + UUID.randomUUID());
            return credentials.create(CALLER, system.id(), "ci", Set.of("billing:invoice:read")).plaintextKey();
        });
    }

    private int status(String presentedKey) throws Exception {
        return request(presentedKey).getResponse().getStatus();
    }

    private MvcResult request(String presentedKey) throws Exception {
        String[] parts = presentedKey.split("_");
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((parts[1] + ":" + parts[2]).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", "billing")
                        .header("Authorization", basic))
                .andReturn();
    }
}
