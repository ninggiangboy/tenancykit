package dev.ngb.mailroom.boot.config;

import dev.ngb.core.event.EventInbox;
import dev.ngb.core.feign.ClientCredentialsTokenProvider;
import dev.ngb.core.feign.FeignFailures;
import dev.ngb.core.feign.FeignSettings;
import dev.ngb.core.feign.TokenProvider;
import dev.ngb.core.feign.TokenSettings;
import dev.ngb.core.messaging.broker.EnvelopeCodec;
import dev.ngb.core.messaging.inbound.AmqpInboundDispatcher;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.replica.ReplicaState;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.suite.security.SuiteActorResolver;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.sync.ReplicaBootstrap;
import dev.ngb.core.sync.ReplicaProperties;
import dev.ngb.core.sync.SnapshotClient;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.boot.health.ReplicaStateHealthContributor;
import dev.ngb.mailroom.boot.replica.PlatformEventsHandler;
import dev.ngb.mailroom.boot.replica.TransitionSink;
import dev.ngb.mailroom.boot.replica.feign.FeignSnapshotTransport;
import dev.ngb.mailroom.platform.adapter.platform.persistence.JooqTenantDirectory;
import dev.ngb.mailroom.platform.adapter.platform.persistence.JooqUserDirectory;
import dev.ngb.mailroom.platform.api.replica.TenantDirectory;
import dev.ngb.mailroom.platform.api.replica.UserDirectory;
import dev.ngb.mailroom.platform.application.platform.service.TenantDirectoryService;
import dev.ngb.mailroom.platform.application.platform.service.UserDirectoryService;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the suite core into email: {@code core-platform-sync} fetches the snapshot and rebuilds the replica,
 * {@code core-replica} applies the five guarded upserts and {@code core-suite} reads the host, the actor and the
 * entitlement from it.
 *
 * <p>The listener, its dispatcher and the rebuild run only in the worker profile. Billing profiles both: an unprofiled
 * {@code ReplicaBootstrap} needs a container bean the api profile would otherwise have to provide, and an unprofiled
 * container makes the api runtime a second consumer of the replica queue.
 */
@Configuration(proxyBeanMethods = false)
class ReplicaConfiguration {

    @Bean
    ReplicaProperties replicaProperties(SuiteProperties suite) {
        SuiteProperties.Replica replica = suite.replica();
        return new ReplicaProperties(suite.appCode(), new ReplicaProperties.Replica(
                replica.platformBaseUrl(), replica.clientId(), replica.clientSecret(),
                replica.rebuildOnStart(), replica.pageLimit(), replica.requestTimeout()));
    }

    @Bean
    ReplicaWriter replicaWriter(DSLContext dsl) {
        return new ReplicaWriter(dsl);
    }

    // Named for the rebuild marker, not `replicaState`: the health contributor takes that exact bean name so the
    // actuator key is `replicaState`, the one both profile files include.
    @Bean
    ReplicaState replicaRebuildState(DSLContext dsl) {
        return new ReplicaState(dsl);
    }

    @Bean
    TenantDirectory tenantDirectory(DSLContext dsl) {
        return new TenantDirectoryService(new JooqTenantDirectory(dsl));
    }

    @Bean
    UserDirectory userDirectory(DSLContext dsl) {
        return new UserDirectoryService(new JooqUserDirectory(dsl));
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
            SuiteProperties suite,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String userIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String userJwkSetUri) {
        SuiteJwtDecoders.Issuer platform =
                new SuiteJwtDecoders.Issuer(suite.replica().platformIssuer(), suite.replica().platformJwkSetUri());
        SuiteJwtDecoders.Issuer user = userJwkSetUri == null || userJwkSetUri.isBlank()
                ? null
                : new SuiteJwtDecoders.Issuer(userIssuer, userJwkSetUri);
        return new SuiteJwtDecoders(platform, user, properties.appCode());
    }

    @Bean
    SnapshotClient snapshotClient(ReplicaProperties properties, SuiteProperties suite, Clock clock) {
        ReplicaProperties.Replica replica = properties.replica();
        FeignSettings settings = new FeignSettings(
                "Platform",
                replica.platformBaseUrl(),
                replica.requestTimeout(),
                replica.requestTimeout(),
                suite.replica().maxAttempts(),
                suite.replica().retryBackoff());
        TokenSettings tokenSettings = TokenSettings.forBaseUrl(
                replica.platformBaseUrl(),
                replica.clientId(),
                replica.clientSecret(),
                "platform",
                replica.requestTimeout(),
                replica.requestTimeout(),
                suite.replica().refreshSkew());
        TokenProvider tokens = new ClientCredentialsTokenProvider(tokenSettings, clock, FeignFailures.core());
        return new SnapshotClient(FeignSnapshotTransport.create(settings, tokens, FeignFailures.core()));
    }

    @Bean
    PlatformEventsHandler platformEventsHandler(
            ReplicaWriter writer, TenantDirectory tenants, UserDirectory users, List<TransitionSink> sinks) {
        return new PlatformEventsHandler(writer, tenants, users, sinks);
    }

    /**
     * The platform.events consumer. {@code autoStartup=false} is deliberate: {@link ReplicaBootstrap} starts it only
     * after the snapshot load finishes, so messages that arrive meanwhile wait in the durable queue and the version
     * guard drops whatever the snapshot already covered.
     */
    @Bean
    @Profile("worker")
    SimpleMessageListenerContainer platformEventsContainer(
            ConnectionFactory connectionFactory, AmqpInboundDispatcher dispatcher, SuiteProperties suite) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setBeanName("email-platform-events");
        container.setQueueNames(suite.replica().queue());
        container.setAutoStartup(false);
        container.setMessageListener(message -> dispatcher.onMessage(message.getBody()));
        return container;
    }

    @Bean
    @Profile("worker")
    ReplicaBootstrap replicaBootstrap(
            ReplicaProperties properties, ReplicaWriter writer, ReplicaState state, SnapshotClient snapshots,
            UnitOfWork unitOfWork, TenantContext tenantContext, SimpleMessageListenerContainer platformEventsContainer,
            Clock clock) {
        return new ReplicaBootstrap(
                properties, writer, state, snapshots, unitOfWork, tenantContext, platformEventsContainer::start, clock);
    }

    /** The dispatcher the replica queue's container calls. Both runtimes create it; only the worker owns the container. */
    @Bean
    @Profile({"api", "worker"})
    AmqpInboundDispatcher inboundDispatcher(
            JsonMapper json, List<InboundEventHandler> handlers, EventInbox inbox, UnitOfWork unitOfWork,
            TenantContext tenantContext) {
        return new AmqpInboundDispatcher(
                new EnvelopeCodec(json), handlers, inbox, unitOfWork, tenantContext,
                // A child row (a user's grants) can arrive before its tenant when the producer backed off on the
                // parent, so the replica waits about a minute for the parent before dead-lettering.
                AmqpInboundDispatcher.retry(8, 500, 20_000));
    }

    /**
     * The readiness contributor, deliberately bean-named {@code replicaState} so the actuator health key is
     * {@code replicaState} — the exact key both profile files include.
     */
    @Bean
    HealthIndicator replicaState(ReplicaState state) {
        return new ReplicaStateHealthContributor(state);
    }
}
