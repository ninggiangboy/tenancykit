package dev.ngb.booking.gate.domain.token;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The opaque token round-trips, rejects tampering and keeps the tenant and scopes inside the signed body. */
class CustomerTokenTest {

    private static final CustomerTokens.Keys KEYS =
            new CustomerTokens.Keys("k1", Map.of("k1", "a-test-secret", "k0", "an-older-secret"));

    @Test
    void aTokenRoundTripsWithItsClaims() {
        CustomerClaims claims = new CustomerClaims(
                TenantId.of(UUID.randomUUID().toString()),
                "cust-1",
                Set.of(GateScope.browse, GateScope.hold),
                UUID.randomUUID(),
                Instant.now().plusSeconds(600),
                "k1");
        String token = CustomerTokens.sign(claims, KEYS);

        CustomerClaims verified = CustomerTokens.verify(token, KEYS).orElseThrow();
        assertThat(verified.tenantId()).isEqualTo(claims.tenantId());
        assertThat(verified.customerRef()).isEqualTo(claims.customerRef());
        assertThat(verified.scopes()).isEqualTo(claims.scopes());
        assertThat(verified.offeringId()).isEqualTo(claims.offeringId());
        assertThat(verified.expiresAt()).isEqualTo(claims.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(verified.keyId()).isEqualTo(claims.keyId());
    }

    @Test
    void everyTamperIsEmpty() {
        CustomerClaims claims = new CustomerClaims(
                TenantId.of(UUID.randomUUID().toString()),
                "cust-1",
                Set.of(GateScope.browse),
                null,
                Instant.now().plusSeconds(600),
                "k1");
        String token = CustomerTokens.sign(claims, KEYS);

        assertThat(CustomerTokens.verify(token.substring(0, token.length() - 2), KEYS)).isEmpty();
        assertThat(CustomerTokens.verify("x" + token, KEYS)).isEmpty();
        assertThat(CustomerTokens.verify(token, new CustomerTokens.Keys("k2", Map.of("k2", "another"))))
                .isEmpty();
    }

    @Test
    void aTokenSignedByAnOlderKeyStillVerifies() {
        CustomerClaims claims = new CustomerClaims(
                TenantId.of(UUID.randomUUID().toString()),
                "cust-1",
                Set.of(GateScope.waitlist),
                null,
                Instant.now().plusSeconds(600),
                "k0");
        String token = CustomerTokens.sign(claims, new CustomerTokens.Keys("k0", Map.of("k0", "an-older-secret")));

        CustomerClaims verified = CustomerTokens.verify(token, KEYS).orElseThrow();
        assertThat(verified.tenantId()).isEqualTo(claims.tenantId());
        assertThat(verified.customerRef()).isEqualTo(claims.customerRef());
        assertThat(verified.scopes()).isEqualTo(claims.scopes());
        assertThat(verified.offeringId()).isEqualTo(claims.offeringId());
        assertThat(verified.expiresAt()).isEqualTo(claims.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(verified.keyId()).isEqualTo(claims.keyId());
    }
}
