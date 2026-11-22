package dev.ngb.billing.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This deployment's part in the suite: its app code, the Platform replica it keeps current and the broker it
 * produces to.
 *
 * @param appCode this app's code, its token audience and its entitlement key
 */
@ConfigurationProperties(prefix = "suite")
public record SuiteProperties(
        @DefaultValue("billing") String appCode, Replica replica, Messaging messaging) {

    /**
     * The Platform replica: where the snapshot lives and how to authenticate to it.
     */
    public record Replica(
            @DefaultValue("http://localhost:8082") String platformBaseUrl,
            @DefaultValue("billing") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("true") boolean rebuildOnStart,
            @DefaultValue("200") int pageLimit,
            @DefaultValue("PT10S") Duration requestTimeout,
            @DefaultValue("http://localhost:8082") String platformIssuer,
            @DefaultValue("http://localhost:8082/.well-known/jwks.json") String platformJwkSetUri,
            @DefaultValue("billing.platform-events") String queue,
            @DefaultValue("platform.events") String exchange,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.5S") Duration retryBackoff,
            @DefaultValue("PT30S") Duration refreshSkew) {}

    /**
     * The broker: the exchange this app produces to, the relay's retry policy and the document version.
     */
    public record Messaging(String exchange, Relay relay, Document document) {

        public record Relay(
                @DefaultValue("50") int batchSize,
                @DefaultValue("10") int maxAttempts,
                @DefaultValue("PT10S") Duration backoffBase,
                @DefaultValue("PT1H") Duration backoffCap,
                @DefaultValue("0.2") double jitter,
                @DefaultValue("PT30S") Duration claimDeadline) {}

        public record Document(@DefaultValue("2") int schemaVersion) {}
    }
}
