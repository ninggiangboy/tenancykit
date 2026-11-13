package dev.ngb.billing.boot.config;

import dev.ngb.billing.boot.replica.feign.FeignSnapshotTransport;
import dev.ngb.core.feign.ClientCredentialsTokenProvider;
import dev.ngb.core.feign.FeignFailures;
import dev.ngb.core.feign.FeignSettings;
import dev.ngb.core.feign.TokenProvider;
import dev.ngb.core.feign.TokenSettings;
import dev.ngb.core.messaging.inbound.AmqpInboundDispatcher;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.replica.ReplicaEventHandler;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.suite.security.SuiteActorResolver;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.sync.ReplicaBootstrap;
import dev.ngb.core.sync.ReplicaProperties;
import dev.ngb.core.sync.SnapshotClient;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the suite core into Billing: `core-platform-sync` fetches the snapshot and rebuilds the replica,
 * `core-replica` applies the five guarded upserts and the platform-events listener, and `core-suite` reads the host,
 * the actor and the entitlement from it. The listener, its dispatcher and the rebuild run only in the worker profile,
 * like every other broker consumer.
 */
@Configuration(proxyBeanMethods = false)
class ReplicaConfiguration {

    @Bean
    ReplicaProperties replicaProperties(
            @Value("${suite.app-code:billing}") String appCode,
            @Value("${suite.replica.platform-base-url:http://localhost:8082}") String platformBaseUrl,
            @Value("${suite.replica.client-id:billing}") String clientId,
            @Value("${suite.replica.client-secret:}") String clientSecret,
            @Value("${suite.replica.rebuild-on-start:true}") boolean rebuildOnStart,
            @Value("${suite.replica.page-limit:200}") int pageLimit,
            @Value("${suite.replica.request-timeout:PT10S}") Duration requestTimeout) {
        return new ReplicaProperties(appCode, new ReplicaProperties.Replica(
                platformBaseUrl, clientId, clientSecret, rebuildOnStart, pageLimit, requestTimeout));
    }

    @Bean
    ReplicaWriter replicaWriter(DSLContext dsl) {
        return new ReplicaWriter(dsl);
    }

    @Bean
    dev.ngb.billing.boot.replica.ReplicaTenants replicaTenants(UnitOfWork unitOfWork, DSLContext dsl) {
        return new dev.ngb.billing.boot.replica.ReplicaTenants(unitOfWork, dsl);
    }

    @Bean
    ReplicaEventHandler replicaEventHandler(ReplicaWriter writer) {
        return new ReplicaEventHandler(writer);
    }

    @Bean
    SuiteHostResolver suiteHostResolver(UnitOfWork unitOfWork, DSLContext dsl) {
        return new SuiteHostResolver(unitOfWork, dsl);
    }

    @Bean
    SuiteEntitlements suiteEntitlements(UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext) {
        return new SuiteEntitlements(unitOfWork, dsl, tenantContext);
    }

    @Bean
    SuiteActorResolver suiteActorResolver(UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext) {
        return new SuiteActorResolver(unitOfWork, dsl, tenantContext);
    }

    @Bean
    SuiteJwtDecoders suiteJwtDecoders(
            ReplicaProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String userIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String userJwkSetUri,
            @Value("${suite.replica.platform-issuer:http://localhost:8082}") String platformIssuer,
            @Value("${suite.replica.platform-jwk-set-uri:http://localhost:8082/.well-known/jwks.json}") String platformJwkSetUri) {
        SuiteJwtDecoders.Issuer platform = new SuiteJwtDecoders.Issuer(platformIssuer, platformJwkSetUri);
        SuiteJwtDecoders.Issuer user = userJwkSetUri == null || userJwkSetUri.isBlank()
                ? null
                : new SuiteJwtDecoders.Issuer(userIssuer, userJwkSetUri);
        return new SuiteJwtDecoders(platform, user, properties.appCode());
    }

    @Bean
    SnapshotClient snapshotClient(
            ReplicaProperties properties,
            Clock clock,
            @Value("${suite.replica.max-attempts:3}") int maxAttempts,
            @Value("${suite.replica.retry-backoff:PT0.5S}") Duration retryBackoff,
            @Value("${suite.replica.refresh-skew:PT30S}") Duration refreshSkew) {
        ReplicaProperties.Replica replica = properties.replica();
        FeignSettings settings = new FeignSettings(
                "Platform",
                replica.platformBaseUrl(),
                replica.requestTimeout(),
                replica.requestTimeout(),
                maxAttempts,
                retryBackoff);
        TokenSettings tokenSettings = TokenSettings.forBaseUrl(
                replica.platformBaseUrl(),
                replica.clientId(),
                replica.clientSecret(),
                "platform",
                replica.requestTimeout(),
                replica.requestTimeout(),
                refreshSkew);
        TokenProvider tokens = new ClientCredentialsTokenProvider(tokenSettings, clock, FeignFailures.core());
        return new SnapshotClient(FeignSnapshotTransport.create(settings, tokens, FeignFailures.core()));
    }

    /**
     * The platform.events consumer. {@code autoStartup=false} is deliberate: {@link ReplicaBootstrap} starts it only
     * after the snapshot load finishes, so messages that arrive meanwhile wait in the durable queue.
     */
    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer platformEventsContainer(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            AmqpInboundDispatcher dispatcher,
            @Value("${suite.replica.queue:billing.platform-events}") String queue) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("platform-events");
        container.setQueueNames(queue);
        container.setAutoStartup(false);
        container.setMessageListener(message -> dispatcher.onMessage(message.getBody()));
        return container;
    }

    /**
     * Loads the snapshots a fresh replica needs, then starts the platform-events listener, so queued messages still
     * wait until the rebuild finishes.
     */
    @Bean
    @Profile("worker")
    ReplicaBootstrap replicaBootstrap(
            ReplicaProperties properties, ReplicaWriter writer, SnapshotClient snapshots, UnitOfWork unitOfWork,
            TenantContext tenantContext, SimpleMessageListenerContainer platformEventsContainer) {
        return new ReplicaBootstrap(
                properties, writer, snapshots, unitOfWork, tenantContext, platformEventsContainer::start);
    }

    /** The inbound handlers the dispatcher calls; only the replica consumes platform.events. */
    @Bean
    @Profile("worker")
    AmqpInboundDispatcher inboundDispatcher(
            tools.jackson.databind.json.JsonMapper json, List<InboundEventHandler> handlers,
            dev.ngb.core.event.EventInbox inbox, UnitOfWork unitOfWork, TenantContext tenantContext) {
        return new AmqpInboundDispatcher(
                new dev.ngb.core.messaging.broker.EnvelopeCodec(json), handlers, inbox, unitOfWork, tenantContext,
                // A child row (a domain, a user's grants) can arrive before its tenant when the producer backed off on
                // the parent, so the replica waits about a minute for the parent before dead-lettering.
                AmqpInboundDispatcher.retry(8, 500, 20_000));
    }
}
