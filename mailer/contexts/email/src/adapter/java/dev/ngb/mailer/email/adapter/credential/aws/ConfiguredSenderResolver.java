package dev.ngb.mailer.email.adapter.credential.aws;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.mailer.email.application.email.exception.UnresolvableSenderException;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SenderMode;
import dev.ngb.mailer.email.application.email.port.SenderResolver;
import dev.ngb.mailer.shared.FromKey;
import dev.ngb.mailer.shared.Recipient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * The one resolver for the whole service. It grows a mode rather than gaining a sibling, so the delivery job never
 * learns which mode it is using:
 *
 * <ul>
 *   <li>no row for this tenant and key — the suite's own configured identity, which is what makes local development and
 *       the first production send work with no tenant setup;</li>
 *   <li>a {@code SHARED} row — that row's region and address, with the task role's ambient credentials;</li>
 *   <li>a {@code TENANT} row — a provider that assumes the tenant's role, with the external id decrypted using the row
 *       id as context;</li>
 *   <li>a row that exists but is {@code DISABLED} — an error, never a fallback. Silently switching identity is worse
 *       than failing.</li>
 * </ul>
 *
 * <p>The reply-to check lives here rather than in an adapter so both transports get it: an arbitrary reply-to turns a
 * DKIM-valid suite email into business-email compromise.
 */
public class ConfiguredSenderResolver implements SenderResolver {

    private final CredentialStore credentials;
    private final SecretCipher cipher;
    private final CredentialProviderCache providers;
    private final String defaultRegion;
    private final String defaultFromAddress;
    private final String defaultFromName;

    public ConfiguredSenderResolver(
            CredentialStore credentials,
            SecretCipher cipher,
            CredentialProviderCache providers,
            String defaultRegion,
            String defaultFromAddress,
            String defaultFromName) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.providers = providers;
        this.defaultRegion = defaultRegion;
        this.defaultFromAddress = defaultFromAddress;
        this.defaultFromName = defaultFromName;
    }

    @Override
    public ResolvedSender resolve(TenantId tenantId, FromKey fromKey, String replyTo) {
        Optional<CredentialStore.Row> active = credentials.findActive(tenantId, fromKey);
        if (active.isEmpty()) {
            if (credentials.findDisabled(tenantId, fromKey).isPresent()) {
                // A disabled row is a deliberate stop, so a send must fail rather than quietly use another identity.
                throw new UnresolvableSenderException("the credential for '" + fromKey.value() + "' is disabled");
            }
            return shared(fromKey, replyTo);
        }
        CredentialStore.Row row = active.get();
        String fromName = row.fromName() == null || row.fromName().isBlank() ? defaultFromName : row.fromName();
        String allowedReplyTo = allowedReplyTo(row.fromAddress(), row.replyToAllowlist(), replyTo, fromKey);
        AwsCredentialsProvider provider =
                row.mode() == SenderMode.TENANT ? assumeRole(row) : defaultProvider();
        return new ResolvedSender(
                row.region(),
                row.fromAddress(),
                fromName,
                allowedReplyTo,
                CredentialProviderCache.identityOf(row),
                new AwsSenderCredentials(provider));
    }

    /** Drops the cached provider for a row, so the next resolve rebuilds it. */
    public void evict(UUID rowId) {
        providers.evict(rowId);
    }

    private ResolvedSender shared(FromKey fromKey, String replyTo) {
        if (defaultFromAddress == null || defaultFromAddress.isBlank()) {
            throw new UnresolvableSenderException(
                    "no credential is registered for '" + fromKey.value() + "' and no default sender is configured");
        }
        String allowedReplyTo = allowedReplyTo(defaultFromAddress, List.of(), replyTo, fromKey);
        return new ResolvedSender(
                defaultRegion,
                defaultFromAddress,
                defaultFromName,
                allowedReplyTo,
                "default:" + defaultFromAddress,
                new AwsSenderCredentials(defaultProvider()));
    }

    /**
     * The task role's ambient credentials, read from the standard chain. Access keys are never read from the environment:
     * a deployment supplies them through the ECS task role and nothing else.
     */
    private static AwsCredentialsProvider defaultProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    private AwsCredentialsProvider assumeRole(CredentialStore.Row row) {
        String externalId;
        try {
            externalId = cipher.decrypt(row.externalIdCiphertext(), row.id().toString());
        } catch (RuntimeException failure) {
            throw new UnresolvableSenderException(
                    "the external id for '" + row.fromKey().value() + "' cannot be decrypted", failure);
        }
        return providers.provider(row, externalId);
    }

    /**
     * A reply-to must be covered by the resolved sender's allowlist. An empty allowlist means "the sender's own domain",
     * which keeps the default identity usable without permitting an arbitrary address.
     */
    private static String allowedReplyTo(String fromAddress, List<String> allowlist, String replyTo, FromKey fromKey) {
        if (replyTo == null || replyTo.isBlank()) {
            return null;
        }
        Recipient recipient;
        try {
            recipient = new Recipient(replyTo);
        } catch (IllegalArgumentException invalid) {
            throw new UnresolvableSenderException("the reply-to is not a usable address", invalid);
        }
        String domain = recipient.domain().toLowerCase();
        boolean allowed;
        if (allowlist.isEmpty()) {
            allowed = domain.equals(fromAddress.substring(fromAddress.indexOf('@') + 1).toLowerCase());
        } else {
            allowed = allowlist.stream()
                    .map(entry -> entry.strip().toLowerCase())
                    .anyMatch(entry -> entry.equals("*")
                            || entry.equals(domain)
                            || (entry.startsWith("*.") && domain.endsWith(entry.substring(1))));
        }
        if (!allowed) {
            throw new UnresolvableSenderException(
                    "the reply-to is not in the allowlist of '" + fromKey.value() + "'");
        }
        return recipient.address();
    }
}
