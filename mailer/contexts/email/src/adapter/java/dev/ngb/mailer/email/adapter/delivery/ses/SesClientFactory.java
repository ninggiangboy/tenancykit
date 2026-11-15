package dev.ngb.mailer.email.adapter.delivery.ses;

import dev.ngb.mailer.email.adapter.credential.aws.AwsSenderCredentials;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SenderCredentials;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

/**
 * Builds and caches one SES client per credential, and closes them on shutdown.
 *
 * <p>Two decisions are load-bearing:
 * <ul>
 *   <li><b>retries are off.</b> The SDK retries by default, and the ledger already retries with backoff. Leaving both on
 *       turns one ledger attempt into three API calls, so the attempt count in the transcript would no longer describe
 *       what happened.</li>
 *   <li><b>one client per {@code (region, credential)}.</b> A client built per send means connection churn and
 *       throttling at moderate volume, and an assumed-role provider rebuilt per send throws away credentials it just
 *       fetched.</li>
 * </ul>
 *
 * <p>{@code endpointOverride} is what lets the same production adapter be pointed at a local emulator or a wire stub
 * without a third implementation.
 */
public class SesClientFactory implements AutoCloseable {

    private final Map<String, SesV2Client> clients = new ConcurrentHashMap<>();
    private final String endpointOverride;
    private final Object lock = new Object();

    public SesClientFactory(String endpointOverride) {
        this.endpointOverride = endpointOverride;
    }

    public SesV2Client client(ResolvedSender sender) {
        return clients.computeIfAbsent(sender.identity(), key -> build(sender));
    }

    /** Drops and closes the client for one credential identity, so a rotated or disabled credential stops being used. */
    public void evict(String identityPrefix) {
        synchronized (lock) {
            clients.keySet().removeIf(key -> {
                if (!key.startsWith(identityPrefix)) {
                    return false;
                }
                SesV2Client removed = clients.get(key);
                if (removed != null) {
                    removed.close();
                }
                return true;
            });
        }
    }

    private SesV2Client build(ResolvedSender sender) {
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(Region.of(sender.region()))
                .credentialsProvider(provider(sender.credentials()))
                .overrideConfiguration(override -> override
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()));
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider provider(SenderCredentials credentials) {
        if (credentials instanceof AwsSenderCredentials aws) {
            return aws.provider();
        }
        throw new IllegalStateException("the SES adapter needs AWS credentials, but the resolver returned "
                + credentials.getClass().getSimpleName());
    }

    @Override
    public void close() {
        synchronized (lock) {
            clients.values().forEach(SesV2Client::close);
            clients.clear();
        }
    }
}
