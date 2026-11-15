package dev.ngb.mailer.email.adapter.credential.aws;

import dev.ngb.mailer.email.application.email.port.CredentialStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * Caches one assumed-role provider per credential row and version, and can drop it.
 *
 * <p>The cache is what keeps STS out of the delivery path: building a provider per send means an {@code AssumeRole}
 * call per send, which throttles at moderate volume. The version in the key is what makes a rotation visible without a
 * restart, and the explicit {@link #evict} is what makes a disabled credential stop being used — a credential that keeps
 * sending until the process restarts is not a control.
 *
 * <p>Every provider created here is closed when it is evicted or when the service shuts down.
 */
public class CredentialProviderCache implements AutoCloseable {

    /** Role chaining caps a session at one hour, because the task role's credentials are already temporary. */
    private static final int SESSION_SECONDS = 3600;

    private final Map<String, StsAssumeRoleCredentialsProvider> providers = new LinkedHashMap<>();
    private final int maxEntries;

    public CredentialProviderCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
    }

    /** The provider for this row version, built (and cached) on first use. */
    public AwsCredentialsProvider provider(CredentialStore.Row row, String externalId) {
        String key = keyOf(row);
        synchronized (providers) {
            StsAssumeRoleCredentialsProvider cached = providers.get(key);
            if (cached != null) {
                return cached;
            }
            evictOldestWhileFull();
            StsAssumeRoleCredentialsProvider created = build(row, externalId);
            providers.put(key, created);
            return created;
        }
    }

    /** Identifier a caller can use to release a credential's client and provider together. */
    public static String identityOf(CredentialStore.Row row) {
        return "row:" + row.id() + ":" + row.version();
    }

    /** Drops every cached version of one row. Called on disable, on rotation, and after an {@code AccessDenied}. */
    public void evict(UUID rowId) {
        String prefix = "row:" + rowId + ":";
        synchronized (providers) {
            Iterator<Map.Entry<String, StsAssumeRoleCredentialsProvider>> entries = providers.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, StsAssumeRoleCredentialsProvider> entry = entries.next();
                if (entry.getKey().startsWith(prefix)) {
                    entry.getValue().close();
                    entries.remove();
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (providers) {
            providers.values().forEach(StsAssumeRoleCredentialsProvider::close);
            providers.clear();
        }
    }

    private void evictOldestWhileFull() {
        Iterator<Map.Entry<String, StsAssumeRoleCredentialsProvider>> entries = providers.entrySet().iterator();
        while (providers.size() >= maxEntries && entries.hasNext()) {
            Map.Entry<String, StsAssumeRoleCredentialsProvider> oldest = entries.next();
            oldest.getValue().close();
            entries.remove();
        }
    }

    private static String keyOf(CredentialStore.Row row) {
        return identityOf(row);
    }

    private static StsAssumeRoleCredentialsProvider build(CredentialStore.Row row, String externalId) {
        StsClient sts = StsClient.builder().region(Region.of(row.region())).build();
        // The session name carries the version so CloudTrail distinguishes a rotated credential from the old one.
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .refreshRequest(request -> request
                        .roleArn(row.roleArn())
                        .externalId(externalId)
                        .roleSessionName("mailer-" + row.tenantId() + "-v" + row.version())
                        .durationSeconds(SESSION_SECONDS))
                .build();
    }
}
