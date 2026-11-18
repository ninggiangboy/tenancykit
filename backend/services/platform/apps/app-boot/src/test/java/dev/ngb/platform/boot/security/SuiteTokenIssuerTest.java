package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.adapter.auth.service.ConfiguredServiceClients;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The suite token contract: expires in ten minutes, names one audience, carries only that audience's scopes. */
class SuiteTokenIssuerTest {

    private final SuiteTokenIssuer issuer = new SuiteTokenIssuer("http://platform.test", null, null);

    @Test
    void aServiceTokenVerifiesAgainstThePublishedJwks() {
        SuiteTokenIssuer.Issued issued = issuer.issueService("billing", Set.of("platform:replica:read"), "platform");

        Jwt jwt = issuer.decoder().decode(issued.token());

        assertThat(jwt.getIssuer().toString()).isEqualTo("http://platform.test");
        assertThat(jwt.getSubject()).isEqualTo("service/billing");
        assertThat(jwt.getAudience()).containsExactly("platform");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("platform:replica:read");
        assertThat(issued.expiresIn()).isEqualTo(600);
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(600);
        assertThat(issuer.jwks()).containsKey("keys");
    }

    @Test
    void aTenantSystemTokenCarriesTheTenantAndTheSystem() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        TenantSystemId systemId = new TenantSystemId(UUID.randomUUID());

        SuiteTokenIssuer.Issued issued =
                issuer.issueTenantSystem(systemId, tenantId, "acme", Set.of("billing:invoice:read"), "billing");

        Jwt jwt = issuer.decoder().decode(issued.token());
        assertThat(jwt.getSubject()).isEqualTo("tenant-system/" + systemId);
        assertThat(jwt.getAudience()).containsExactly("billing");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsString("tenant_code")).isEqualTo("acme");
        assertThat(jwt.getClaimAsString("tenant_system_id")).isEqualTo(systemId.toString());
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("billing:invoice:read");
    }

    @Test
    void aWrongSecretIsNotAccepted() {
        ConfiguredServiceClients clients = new ConfiguredServiceClients(List.of(new ConfiguredServiceClients.Configured(
                "billing", ConfiguredServiceClients.sha256("right"), List.of("platform:replica:read"))));

        Optional<ConfiguredServiceClients.Client> wrong = clients.authenticate("billing", "wrong");
        Optional<ConfiguredServiceClients.Client> right = clients.authenticate("billing", "right");

        assertThat(wrong).isEmpty();
        assertThat(right).isPresent();
        assertThat(clients.authenticate("unknown", "right")).isEmpty();
    }
}
