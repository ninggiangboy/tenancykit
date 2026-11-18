package dev.ngb.platform.integration.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A credential's life: it is usable while it is ACTIVE and before its end, a rotation shortens that end once, and a
 * revocation is immediate, permanent and does not depend on the rotation window.
 */
class ApiCredentialTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final TenantSystemId SYSTEM = new TenantSystemId(UUID.randomUUID());

    private static ApiCredential issued() {
        return ApiCredential.issue(new ApiCredentialId(UUID.randomUUID()), TENANT, SYSTEM,
                ApiKey.generate(new SecureRandom()), Set.of("billing:invoice:read"), "svc", NOW);
    }

    @Test
    void aFreshCredentialIsUsableAndMatchesOnlyItsOwnSecret() {
        ApiCredential credential = issued();
        ApiKey.Generated key = ApiKey.generate(new SecureRandom());
        ApiCredential other = ApiCredential.issue(new ApiCredentialId(UUID.randomUUID()), TENANT, SYSTEM, key, Set.of(), "", NOW);

        assertThat(credential.isUsable(NOW)).isTrue();
        assertThat(credential.status()).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(credential.version()).isEqualTo(1);
        assertThat(credential.expiresAt()).isNull();
        assertThat(other.matches(key.secret())).isTrue();
        assertThat(other.matches(key.secret() + "x")).isFalse();
    }

    @Test
    void anExpiredCredentialIsNotUsable() {
        ApiCredential credential = ApiCredential.restore(new ApiCredentialId(UUID.randomUUID()), TENANT, SYSTEM, "key",
                ApiKey.hash("secret"), Set.of(), "svc", CredentialStatus.ACTIVE, NOW, NOW.plusSeconds(60), null, 1);

        assertThat(credential.isUsable(NOW)).isTrue();
        assertThat(credential.isUsable(NOW.plusSeconds(60))).as("the end instant is not usable").isFalse();
    }

    @Test
    void aRotationShortensTheEndOnceAndNeverLengthensIt() {
        ApiCredential credential = issued();

        assertThat(credential.expireAt(NOW.plusSeconds(3600))).isTrue();
        assertThat(credential.version()).isEqualTo(2);

        assertThat(credential.expireAt(NOW.plusSeconds(7200))).as("a later end never extends an overlap").isFalse();
        assertThat(credential.expireAt(NOW.plusSeconds(3600))).as("the same end is a no-op").isFalse();

        assertThat(credential.expireAt(NOW.plusSeconds(600))).isTrue();
        assertThat(credential.version()).isEqualTo(3);
        assertThat(credential.isUsable(NOW.plusSeconds(600))).isFalse();
    }

    @Test
    void aRevocationIsImmediatePermanentAndCountsOnce() {
        ApiCredential credential = issued();

        assertThat(credential.revoke(NOW)).isTrue();

        assertThat(credential.status()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(credential.revokedAt()).isEqualTo(NOW);
        assertThat(credential.isUsable(NOW)).isFalse();
        assertThat(credential.version()).isEqualTo(2);
        assertThat(credential.revoke(NOW.plusSeconds(1))).as("revoking twice writes nothing").isFalse();
        assertThat(credential.version()).isEqualTo(2);
        assertThat(credential.expireAt(NOW.plusSeconds(3600))).as("an overlap cannot resurrect it").isTrue();
        assertThat(credential.isUsable(NOW.plusSeconds(3600))).isFalse();
    }

    @Test
    void theHashItExposesIsACopy() {
        ApiCredential credential = issued();

        byte[] first = credential.secretHash();
        first[0] = (byte) ~first[0];

        assertThat(credential.secretHash()).isNotEqualTo(first);
    }

    @Test
    void aLabelIsStrippedAndBounded() {
        assertThat(ApiCredential.issue(new ApiCredentialId(UUID.randomUUID()), TENANT, SYSTEM,
                        ApiKey.generate(new SecureRandom()), Set.of(), "  svc  ", NOW).label())
                .isEqualTo("svc");
        assertThatThrownBy(() -> ApiCredential.issue(new ApiCredentialId(UUID.randomUUID()), TENANT, SYSTEM,
                        ApiKey.generate(new SecureRandom()), Set.of(), "x".repeat(101), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theToStringNeverHoldsTheSecret() {
        ApiCredential credential = issued();

        assertThat(credential.toString()).contains("status=ACTIVE").doesNotContain("secret");
    }
}
