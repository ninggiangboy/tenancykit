package dev.ngb.loyalty.boot.config;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.observability.MetricNames;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.util.Backoff;
import dev.ngb.core.webhook.RetrySchedule;
import dev.ngb.loyalty.webhook.adapter.delivery.crypto.SharedPayloadSigner;
import dev.ngb.loyalty.webhook.adapter.delivery.http.SharedWebhookSender;
import dev.ngb.loyalty.webhook.adapter.delivery.json.JacksonEventBodyWriter;
import dev.ngb.loyalty.webhook.adapter.delivery.messaging.WebhookEventListener;
import dev.ngb.loyalty.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.loyalty.webhook.adapter.delivery.persistence.JooqWebhookDeliveryRepository;
import dev.ngb.loyalty.webhook.adapter.endpoint.crypto.RandomSigningKeys;
import dev.ngb.loyalty.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.loyalty.webhook.adapter.endpoint.persistence.JooqWebhookEndpointRepository;
import dev.ngb.loyalty.webhook.adapter.endpoint.schema.ClasspathEventTypeCatalog;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.loyalty.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import dev.ngb.loyalty.webhook.application.delivery.command.RecordDeliveriesUseCase;
import dev.ngb.loyalty.webhook.application.delivery.port.DeliveryLagRecorder;
import dev.ngb.loyalty.webhook.application.delivery.port.EventBodyWriter;
import dev.ngb.loyalty.webhook.application.delivery.port.PayloadSigner;
import dev.ngb.loyalty.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.loyalty.webhook.application.delivery.port.WebhookSender;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.loyalty.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.ManageEndpointUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.loyalty.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.loyalty.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.loyalty.webhook.application.endpoint.service.WebhookSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbox relay and the webhook module. It holds no business rules: the mechanics live in core-webhook and the
 * delivery state in the webhook context.
 *
 * <p>The relay publishes each due outbox row as a Spring event in its own transaction; the webhook module listens to the
 * same events in that same transaction and queues one delivery per subscribed endpoint, which the worker then claims and
 * sends. That is the whole path, so loyalty has no {@code BrokerRelay} and declares no exchange.
 */
@Configuration(proxyBeanMethods = false)
class WebhookConfiguration {

    @Bean
    WebhookSettings webhookSettings(LoyaltySettings settings) {
        LoyaltySettings.Relay relay = settings.relay();
        LoyaltySettings.Webhooks webhooks = settings.webhooks();
        return new WebhookSettings(
                webhooks.allowNonPublicTargets(),
                webhooks.batchSize(),
                new RetrySchedule(
                        webhooks.maxAttempts(),
                        new Backoff(webhooks.backoffBase(), webhooks.backoffCap(), relay.jitter()),
                        webhooks.lease()),
                webhooks.retention());
    }

    @Bean
    WebhookEndpointRepository webhookEndpointRepository(DSLContext dsl) {
        return new JooqWebhookEndpointRepository(dsl);
    }

    @Bean
    WebhookDeliveryRepository webhookDeliveryRepository(DSLContext dsl) {
        return new JooqWebhookDeliveryRepository(dsl);
    }

    @Bean
    EndpointReadRepository endpointReadRepository(DSLContext dsl) {
        return new JooqWebhookEndpointReadRepository(dsl);
    }

    @Bean
    DeliveryReadRepository deliveryReadRepository(DSLContext dsl) {
        return new JooqWebhookDeliveryReadRepository(dsl);
    }

    @Bean
    EndpointQueries endpointQueries(UnitOfWork unitOfWork, EndpointReadRepository endpoints) {
        return new EndpointQueries(unitOfWork, endpoints);
    }

    @Bean
    DeliveryQueries deliveryQueries(UnitOfWork unitOfWork, DeliveryReadRepository deliveries) {
        return new DeliveryQueries(unitOfWork, deliveries);
    }

    @Bean
    AesGcmSecretCipher secretCipher(LoyaltySettings settings) {
        return new AesGcmSecretCipher(
                "loyalty.secrets", settings.webhooks().masterKey(), settings.webhooks().masterKeyId());
    }

    @Bean
    SigningKeys signingKeys() {
        return new RandomSigningKeys();
    }

    @Bean
    PayloadSigner payloadSigner() {
        return new SharedPayloadSigner();
    }

    @Bean(destroyMethod = "close")
    SharedWebhookSender webhookSender(LoyaltySettings settings) {
        LoyaltySettings.Webhooks webhooks = settings.webhooks();
        return new SharedWebhookSender(
                webhooks.allowNonPublicTargets(),
                webhooks.maxConnections(),
                webhooks.maxConnectionsPerRoute(),
                webhooks.connectTimeout(),
                webhooks.connectionRequestTimeout(),
                webhooks.readTimeout(),
                webhooks.deadline());
    }

    @Bean
    EventBodyWriter eventBodyWriter(JsonMapper json) {
        return new JacksonEventBodyWriter(json);
    }

    /** The catalog is the event schemas the build packages, so a type the app does not send cannot be subscribed to. */
    @Bean
    EventTypeCatalog eventTypeCatalog() {
        return new ClasspathEventTypeCatalog();
    }

    @Bean
    CreateEndpointUseCase createEndpointUseCase(
            UnitOfWork unitOfWork,
            WebhookEndpointRepository endpoints,
            SecretCipher cipher,
            SigningKeys keys,
            EventTypeCatalog catalog,
            WebhookSettings settings,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateEndpointUseCase(
                unitOfWork, endpoints, cipher, keys, catalog, settings, tenantContext, ids, clock);
    }

    @Bean
    ManageEndpointUseCase manageEndpoint(
            UnitOfWork unitOfWork,
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            SecretCipher cipher,
            SigningKeys keys,
            EventTypeCatalog catalog,
            WebhookSettings settings,
            Clock clock) {
        return new ManageEndpointUseCase(unitOfWork, endpoints, deliveries, cipher, keys, catalog, settings, clock);
    }

    @Bean
    ManageDeliveryUseCase manageDelivery(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, IdGenerator ids, Clock clock) {
        return new ManageDeliveryUseCase(unitOfWork, deliveries, ids, clock);
    }

    @Bean
    PurgeDeliveriesUseCase purgeDeliveriesUseCase(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, WebhookSettings settings, Clock clock) {
        return new PurgeDeliveriesUseCase(unitOfWork, deliveries, settings, clock);
    }

    @Bean
    RecordDeliveriesUseCase recordDeliveriesUseCase(
            UnitOfWork unitOfWork,
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            EventInbox inbox,
            EventBodyWriter bodies,
            IdGenerator ids,
            Clock clock) {
        return new RecordDeliveriesUseCase(unitOfWork, endpoints, deliveries, inbox, bodies, ids, clock);
    }

    @Bean
    DeliveryLagRecorder deliveryLagRecorder(MeterRegistry meters) {
        return meters.timer(MetricNames.in("loyalty", MetricNames.WEBHOOK_LAG))::record;
    }

    @Bean
    DeliverWebhooksUseCase deliverWebhooksUseCase(
            UnitOfWork unitOfWork,
            WebhookDeliveryRepository deliveries,
            SecretCipher cipher,
            PayloadSigner signer,
            WebhookSender sender,
            WebhookSettings settings,
            Clock clock,
            DeliveryLagRecorder lags) {
        return new DeliverWebhooksUseCase(
                unitOfWork,
                deliveries,
                cipher,
                signer,
                sender,
                settings,
                clock,
                lags,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    WebhookEventListener webhookEventListener(RecordDeliveriesUseCase recordDeliveries) {
        return new WebhookEventListener(recordDeliveries);
    }

    /**
     * A relay that publishes to nobody is a silent no-op: every outbox row would be marked delivered with no delivery
     * recorded. This bean makes a lost {@link EventListener} a startup failure instead.
     */
    @Bean
    WebhookListenerWiring webhookListenerWiring(WebhookEventListener listener, OutboxRelay relay) {
        return new WebhookListenerWiring(listener, relay);
    }

    /** The startup assertion that the outbox event the relay publishes is the one the webhook module consumes. */
    record WebhookListenerWiring(WebhookEventListener listener, OutboxRelay relay) {

        WebhookListenerWiring {
            if (listener == null || relay == null) {
                throw new IllegalStateException("the outbox relay or the webhook listener is missing");
            }
            Method consumes = Arrays.stream(WebhookEventListener.class.getMethods())
                    .filter(method -> method.isAnnotationPresent(EventListener.class))
                    .filter(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[] {EventEnvelope.class}))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "the webhook listener no longer handles outbox events: deliveries would never be queued"));
            if (consumes.getParameterCount() != 1) {
                throw new IllegalStateException("the webhook listener must take exactly the outbox event");
            }
        }
    }
}
