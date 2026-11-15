package dev.ngb.mailer.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import dev.ngb.mailer.email.adapter.credential.aws.AwsSenderCredentials;
import dev.ngb.mailer.email.adapter.credential.aws.ConfiguredSenderResolver;
import dev.ngb.mailer.email.adapter.credential.aws.CredentialProviderCache;
import dev.ngb.mailer.email.application.email.exception.UnresolvableSenderException;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SenderMode;
import dev.ngb.mailer.shared.FromKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The resolver is where cross-tenant confusion would happen, so its negative cases are the ones that matter: another
 * tenant's row must be invisible, a disabled row must fail rather than fall back, and a ciphertext moved to another row
 * must not decrypt.
 */
class CredentialResolutionTest {

    private static final String MASTER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final TenantId TENANT_A = new TenantId(UUID.randomUUID());
    private static final TenantId TENANT_B = new TenantId(UUID.randomUUID());
    private static final FromKey KEY = new FromKey("marketing");

    private final SecretCipher cipher = new AesGcmSecretCipher("mailer.secrets", MASTER_KEY, "k1");
    private final InMemoryCredentialStore store = new InMemoryCredentialStore();
    private final CredentialProviderCache providers = new CredentialProviderCache(8);

    @AfterEach
    void close() {
        providers.close();
    }

    @Test
    void aTenantWithNoRowUsesTheSuiteDefaults() {
        ResolvedSender resolved = resolver().resolve(TENANT_A, KEY, null);

        assertThat(resolved.fromAddress()).isEqualTo("noreply@example.com");
        assertThat(resolved.identity()).startsWith("default:");
        assertThat(resolved.credentials()).isInstanceOf(AwsSenderCredentials.class);
    }

    @Test
    void aSharedRowOverridesTheRegionAndTheAddress() {
        store.put(TENANT_A, row(TENANT_A, SenderMode.SHARED, "eu-central-1", "billing@tenant-a.example", null, 1));

        ResolvedSender resolved = resolver().resolve(TENANT_A, KEY, null);

        assertThat(resolved.fromAddress()).isEqualTo("billing@tenant-a.example");
        assertThat(resolved.region()).isEqualTo("eu-central-1");
    }

    @Test
    void anotherTenantsRowIsInvisible() {
        store.put(TENANT_A, row(TENANT_A, SenderMode.SHARED, "eu-central-1", "billing@tenant-a.example", null, 1));

        // Tenant B must not inherit tenant A's identity: the lookup carries the tenant predicate, and the fake honours
        // it exactly as row-level security would.
        ResolvedSender resolved = resolver().resolve(TENANT_B, KEY, null);

        assertThat(resolved.fromAddress()).isEqualTo("noreply@example.com");
        assertThat(resolved.identity()).startsWith("default:");
    }

    @Test
    void aDisabledRowIsAnErrorRatherThanAFallback() {
        store.disable(TENANT_A, row(TENANT_A, SenderMode.SHARED, "eu-central-1", "billing@tenant-a.example", null, 2));

        // Silently switching identity is worse than failing: the tenant deliberately stopped this sender.
        assertThatThrownBy(() -> resolver().resolve(TENANT_A, KEY, null))
                .isInstanceOf(UnresolvableSenderException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void aCiphertextMovedToAnotherRowDoesNotDecrypt() {
        UUID originalId = UUID.randomUUID();
        String encrypted = cipher.encrypt("external-id-value", originalId.toString());
        // The row id is the associated data, so a copy onto a different row is undecryptable — which is the whole point
        // of binding it.
        CredentialStore.Row moved = new CredentialStore.Row(
                originalId,
                TENANT_A,
                KEY,
                SenderMode.TENANT,
                "eu-west-1",
                "arn:aws:iam::111122223333:role/mailer",
                encrypted,
                "111122223333",
                "billing@tenant-a.example",
                null,
                List.of(),
                1);
        store.put(TENANT_A, moved);

        ResolvedSender resolved = resolver().resolve(TENANT_A, KEY, null);
        assertThat(resolved.credentials()).isInstanceOf(AwsSenderCredentials.class);

        CredentialStore.Row copied = new CredentialStore.Row(
                UUID.randomUUID(),
                TENANT_A,
                KEY,
                SenderMode.TENANT,
                "eu-west-1",
                "arn:aws:iam::111122223333:role/mailer",
                encrypted,
                "111122223333",
                "billing@tenant-a.example",
                null,
                List.of(),
                2);
        store.put(TENANT_A, copied);

        assertThatThrownBy(() -> resolver().resolve(TENANT_A, KEY, null))
                .isInstanceOf(UnresolvableSenderException.class)
                .hasMessageContaining("external id");
    }

    @Test
    void aTenantRowBindsAnAssumedRoleProviderWithoutCallingAws() {
        UUID rowId = UUID.randomUUID();
        store.put(TENANT_A, new CredentialStore.Row(
                rowId,
                TENANT_A,
                KEY,
                SenderMode.TENANT,
                "eu-west-1",
                "arn:aws:iam::111122223333:role/mailer",
                cipher.encrypt("external-id-value", rowId.toString()),
                "111122223333",
                "billing@tenant-a.example",
                null,
                List.of(),
                3));

        ResolvedSender resolved = resolver().resolve(TENANT_A, KEY, null);

        // Building the provider is lazy: no AssumeRole call happens here, which is what keeps STS out of the delivery
        // path until the first send actually needs credentials.
        assertThat(resolved.region()).isEqualTo("eu-west-1");
        assertThat(resolved.identity()).isEqualTo("row:" + rowId + ":3");
        assertThat(resolved.credentials()).isInstanceOf(AwsSenderCredentials.class);
    }

    @Test
    void aReplyToOutsideTheSendersOwnDomainIsRefused() {
        store.put(TENANT_A, row(TENANT_A, SenderMode.SHARED, "eu-central-1", "billing@tenant-a.example", null, 1));

        assertThat(resolver().resolve(TENANT_A, KEY, "help@tenant-a.example").replyTo())
                .isEqualTo("help@tenant-a.example");

        // An arbitrary reply-to turns a DKIM-valid suite email into business-email compromise.
        assertThatThrownBy(() -> resolver().resolve(TENANT_A, KEY, "attacker@evil.example"))
                .isInstanceOf(UnresolvableSenderException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void anExplicitAllowlistWinsOverTheOwnDomainRule() {
        store.put(
                TENANT_A,
                row(TENANT_A, SenderMode.SHARED, "eu-central-1", "billing@tenant-a.example", List.of("*.reply.example"), 1));

        assertThat(resolver().resolve(TENANT_A, KEY, "help@eu.reply.example").replyTo())
                .isEqualTo("help@eu.reply.example");
        assertThatThrownBy(() -> resolver().resolve(TENANT_A, KEY, "help@tenant-a.example"))
                .isInstanceOf(UnresolvableSenderException.class);
    }

    private ConfiguredSenderResolver resolver() {
        return new ConfiguredSenderResolver(
                store, cipher, providers, "eu-west-1", "noreply@example.com", "TenancyKit");
    }

    private static CredentialStore.Row row(
            TenantId tenantId, SenderMode mode, String region, String fromAddress, List<String> allowlist, long version) {
        return new CredentialStore.Row(
                UUID.randomUUID(),
                tenantId,
                KEY,
                mode,
                region,
                null,
                null,
                null,
                fromAddress,
                null,
                allowlist == null ? List.of() : allowlist,
                version);
    }

    /**
     * A credential table that honours the tenant predicate and the active/disabled split, which is what the production
     * queries do and what row-level security enforces underneath them.
     */
    private static final class InMemoryCredentialStore implements CredentialStore {

        private final Map<String, Row> rows = new LinkedHashMap<>();

        void put(TenantId tenantId, Row row) {
            rows.put(tenantId.value() + "/" + row.fromKey().value() + "/ACTIVE", row);
        }

        void disable(TenantId tenantId, Row row) {
            rows.remove(tenantId.value() + "/" + row.fromKey().value() + "/ACTIVE");
            rows.put(tenantId.value() + "/" + row.fromKey().value() + "/DISABLED", row);
        }

        @Override
        public Optional<Row> findActive(TenantId tenantId, FromKey fromKey) {
            return Optional.ofNullable(rows.get(tenantId.value() + "/" + fromKey.value() + "/ACTIVE"));
        }

        @Override
        public Optional<Row> findDisabled(TenantId tenantId, FromKey fromKey) {
            return Optional.ofNullable(rows.get(tenantId.value() + "/" + fromKey.value() + "/DISABLED"));
        }

        @Override
        public List<Row> findStaleActive(Instant before) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public boolean markVerified(UUID id, long version, Instant at) {
            return true;
        }
    }
}
