package dev.ngb.workflow.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This deployment's part in the suite: its app code and the Platform replica it keeps current.
 *
 * @param appCode this app's code, its token audience and its entitlement key
 */
@ConfigurationProperties(prefix = "suite")
public record SuiteProperties(@DefaultValue("workflow") String appCode, Replica replica) {

    /**
     * The Platform replica: where the snapshot lives, how to authenticate to it and where its events arrive.
     *
     * @param platformIssuer the issuer of a Platform suite token, verified by {@code SuiteJwtDecoders}
     * @param maxAttempts how many times a refused snapshot page is retried
     * @param retryBackoff the wait between snapshot attempts
     * @param refreshSkew how early the snapshot token is refreshed
     */
    public record Replica(
            @DefaultValue("http://localhost:8082") String platformBaseUrl,
            @DefaultValue("workflow") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("true") boolean rebuildOnStart,
            @DefaultValue("200") int pageLimit,
            @DefaultValue("PT10S") Duration requestTimeout,
            @DefaultValue("http://localhost:8082") String platformIssuer,
            @DefaultValue("http://localhost:8082/.well-known/jwks.json") String platformJwkSetUri,
            @DefaultValue("workflow.platform-events") String queue,
            @DefaultValue("platform.events") String exchange,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.5S") Duration retryBackoff,
            @DefaultValue("PT30S") Duration refreshSkew) {}
}
