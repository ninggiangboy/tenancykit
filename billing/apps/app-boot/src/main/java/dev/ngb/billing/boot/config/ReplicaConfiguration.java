package dev.ngb.billing.boot.config;

import dev.ngb.core.messaging.inbound.AmqpInboundDispatcher;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.replica.ReplicaBootstrap;
import dev.ngb.core.replica.ReplicaEventHandler;
import dev.ngb.core.replica.ReplicaProperties;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.replica.SnapshotClient;
import dev.ngb.core.replica.security.ReplicaActorResolver;
import dev.ngb.core.replica.security.SuiteJwtDecoders;
import dev.ngb.core.replica.web.ReplicaEntitlements;
import dev.ngb.core.replica.web.ReplicaHostResolver;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires `core-replica` into Billing: the snapshot it is rebuilt from, the five guarded upserts, the platform-events
 * listener, the hostname resolver, the actor resolver and the two-issuer token decoder.
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
    ReplicaHostResolver replicaHostResolver(UnitOfWork unitOfWork, DSLContext dsl) {
        return new ReplicaHostResolver(unitOfWork, dsl);
    }

    @Bean
    ReplicaEntitlements replicaEntitlements(UnitOfWork unitOfWork, DSLContext dsl) {
        return new ReplicaEntitlements(unitOfWork, dsl);
    }

    @Bean
    ReplicaActorResolver replicaActorResolver(UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext) {
        return new ReplicaActorResolver(unitOfWork, dsl, tenantContext);
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
    SnapshotClient snapshotClient(ReplicaProperties properties) {
        return new SnapshotClient(new PlatformSnapshotTransport(properties, RestClient.builder()));
    }

    /**
     * The platform.events consumer. {@code autoStartup=false} is deliberate: {@link ReplicaBootstrap} starts it only
     * after the snapshot load finishes, so messages that arrive meanwhile wait in the durable queue.
     */
    @Bean
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
    ReplicaBootstrap replicaBootstrap(
            ReplicaProperties properties, ReplicaWriter writer, SnapshotClient snapshots, UnitOfWork unitOfWork,
            TenantContext tenantContext, SimpleMessageListenerContainer platformEventsContainer) {
        return new ReplicaBootstrap(
                properties, writer, snapshots, unitOfWork, tenantContext, platformEventsContainer::start);
    }

    /** The inbound handlers the dispatcher calls; only the replica consumes platform.events. */
    @Bean
    AmqpInboundDispatcher inboundDispatcher(
            tools.jackson.databind.json.JsonMapper json, List<InboundEventHandler> handlers,
            dev.ngb.core.event.EventInbox inbox, UnitOfWork unitOfWork, TenantContext tenantContext) {
        return new AmqpInboundDispatcher(
                new dev.ngb.core.messaging.broker.EnvelopeCodec(json), handlers, inbox, unitOfWork, tenantContext,
                AmqpInboundDispatcher.defaultRetry());
    }

    /** The Spring Web implementation of the snapshot HTTP calls, kept out of core so core stays framework-light. */
    static final class PlatformSnapshotTransport implements SnapshotClient.SnapshotTransport {

        private final ReplicaProperties properties;
        private final RestClient.Builder builder;

        PlatformSnapshotTransport(ReplicaProperties properties, RestClient.Builder builder) {
            this.properties = properties;
            this.builder = builder;
        }

        @Override
        public String serviceToken() {
            var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("audience", "platform");
            @SuppressWarnings("unchecked")
            Map<String, Object> token = builder.build()
                    .post()
                    .uri(properties.replica().platformBaseUrl() + "/oauth/token")
                    .headers(headers -> headers.setBasicAuth(
                            properties.replica().clientId(), properties.replica().clientSecret()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (token == null || token.get("access_token") == null) {
                throw new IllegalStateException("Platform did not answer a snapshot token");
            }
            return (String) token.get("access_token");
        }

        @Override
        public SnapshotClient.Page page(String stream, String after, int limit, String bearerToken) {
            var uri = new StringBuilder(properties.replica().platformBaseUrl())
                    .append("/internal/replica/").append(stream)
                    .append("?limit=").append(limit);
            if (after != null) {
                uri.append("&after=").append(after);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = builder.build()
                    .get()
                    .uri(uri.toString())
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return new SnapshotClient.Page(List.of(), null);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            Object next = body.get("next");
            return new SnapshotClient.Page(items == null ? List.of() : items, next == null ? null : String.valueOf(next));
        }
    }
}
