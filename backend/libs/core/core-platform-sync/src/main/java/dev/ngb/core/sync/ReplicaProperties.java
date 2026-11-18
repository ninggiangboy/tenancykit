package dev.ngb.core.sync;

import java.time.Duration;

/**
 * How one app takes part in the Platform replica.
 *
 * @param appCode the app's code, which is also the token audience and the entitlement key ({@code billing})
 * @param replica where the snapshot lives and how to authenticate to it
 */
public record ReplicaProperties(String appCode, Replica replica) {

    public ReplicaProperties {
        if (appCode == null || appCode.isBlank()) {
            throw new IllegalArgumentException("suite.app-code must name the app");
        }
        if (replica == null) {
            throw new IllegalArgumentException("suite.replica is required for a replica consumer");
        }
    }

    /**
     * @param platformBaseUrl the Platform deployment the snapshot is fetched from
     * @param clientId the service client that obtains a snapshot token
     * @param clientSecret the client's secret; it is compared with the hash Platform stores
     * @param rebuildOnStart rebuild when the replica holds no tenant rows other than the platform pseudo-tenant
     * @param pageLimit the snapshot page size
     * @param requestTimeout how long one snapshot page request may take
     */
    public record Replica(
            String platformBaseUrl,
            String clientId,
            String clientSecret,
            boolean rebuildOnStart,
            int pageLimit,
            Duration requestTimeout) {

        public Replica {
            if (platformBaseUrl == null || platformBaseUrl.isBlank()) {
                throw new IllegalArgumentException("suite.replica.platform-base-url is required");
            }
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("suite.replica.client-id is required");
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException("suite.replica.client-secret is required");
            }
            if (pageLimit < 1 || pageLimit > 500) {
                throw new IllegalArgumentException("suite.replica.page-limit must be between 1 and 500");
            }
            if (requestTimeout == null) {
                throw new IllegalArgumentException("suite.replica.request-timeout is required");
            }
        }
    }
}
