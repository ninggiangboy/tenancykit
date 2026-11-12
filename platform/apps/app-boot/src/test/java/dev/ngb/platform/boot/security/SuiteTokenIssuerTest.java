package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.platform.boot.config.PlatformProperties.ServiceClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The suite token contract: expires in ten minutes, names one audience, carries only that audience's scopes. */
class SuiteTokenIssuerTest {

    private final SuiteTokenIssuer issuer = new SuiteTokenIssuer("http://platform.test", null, null);

    @Test
    void aServiceTokenVerifiesAgainstThePublishedJwks() {
        SuiteTokenIssuer.Issued issued = issuer.issue("billing", List.of("platform:replica:read"), "platform");

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
    void aWrongSecretIsNotAccepted() {
        ServiceClients clients = new ServiceClients(
                List.of(new ServiceClient("billing", ServiceClients.sha256("right"), List.of("platform:replica:read"))));

        Optional<ServiceClient> wrong = clients.authenticate("billing", "wrong");
        Optional<ServiceClient> right = clients.authenticate("billing", "right");

        assertThat(wrong).isEmpty();
        assertThat(right).isPresent();
        assertThat(clients.authenticate("unknown", "right")).isEmpty();
    }
}
