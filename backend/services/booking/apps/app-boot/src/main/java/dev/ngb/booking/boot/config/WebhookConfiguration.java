package dev.ngb.booking.boot.config;

import dev.ngb.booking.webhook.adapter.delivery.crypto.SharedPayloadSigner;
import dev.ngb.booking.webhook.adapter.delivery.http.SharedWebhookSender;
import dev.ngb.booking.webhook.adapter.delivery.json.JacksonEventBodyWriter;
import dev.ngb.booking.webhook.adapter.delivery.messaging.WebhookEventListener;
import dev.ngb.booking.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.booking.webhook.adapter.delivery.persistence.JooqWebhookDeliveryRepository;
import dev.ngb.booking.webhook.adapter.endpoint.crypto.RandomSigningKeys;
import dev.ngb.booking.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.booking.webhook.adapter.endpoint.persistence.JooqWebhookEndpointRepository;
import dev.ngb.booking.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.booking.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.booking.webhook.application.delivery.command.RecordDeliveriesUseCase;
import dev.ngb.booking.webhook.application.delivery.port.DeliveryLagRecorder;
import dev.ngb.booking.webhook.application.delivery.port.EventBodyWriter;
import dev.ngb.booking.webhook.application.delivery.port.PayloadSigner;
import dev.ngb.booking.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.booking.webhook.application.delivery.port.WebhookSender;
import dev.ngb.booking.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.booking.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.booking.webhook.application.endpoint.command.CreateEndpointUseCase;
import dev.ngb.booking.webhook.application.endpoint.command.ManageEndpointUseCase;
import dev.ngb.booking.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.booking.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.booking.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.booking.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.booking.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.booking.webhook.application.endpoint.service.WebhookSettings;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.observability.MetricNames;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.util.Backoff;
import dev.ngb.core.webhook.RetrySchedule;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the webhook context. It holds no business rules: signing, the destination policy, the DNS guard, the sender and
 * the retry schedule live in {@code core-webhook}, and the delivery state lives in the webhook context.
 *
 * <p>The outbox relay is wired in {@code OutboxConfiguration}; the listener below is what it publishes to, inside the
 * relay's own transaction, so a delivery row is committed with the event and a rolled-back change queues nothing.
 */
@Configuration(proxyBeanMethods = false)
class WebhookConfiguration {

    @Bean
    WebhookSettings webhookSettings(BookingSettings settings) {
        BookingSettings.Webhooks webhooks = settings.webhooks();
        return new WebhookSettings(
                webhooks.allowInsecureTargets(),
                webhooks.batchSize(),
                new RetrySchedule(
                        webhooks.maxAttempts(),
                        new Backoff(webhooks.backoffBase(), webhooks.backoffCap(), 0.2),
                        webhooks.lease()));
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
    AesGcmSecretCipher secretCipher(BookingSettings settings) {
        return new AesGcmSecretCipher(
                "booking.secrets", settings.webhooks().masterKey(), settings.webhooks().masterKeyId());
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
    SharedWebhookSender webhookSender(BookingSettings settings) {
        BookingSettings.Webhooks webhooks = settings.webhooks();
        return new SharedWebhookSender(
                webhooks.allowInsecureTargets(),
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

    /** The catalog is the packaged event schemas themselves, so a type booking does not produce cannot be subscribed to. */
    @Bean
    EventTypeCatalog eventTypeCatalog() {
        return new BookingEventTypes(packagedEventTypes());
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
            SecretCipher cipher,
            SigningKeys keys,
            EventTypeCatalog catalog,
            WebhookSettings settings,
            Clock clock) {
        return new ManageEndpointUseCase(unitOfWork, endpoints, cipher, keys, catalog, settings, clock);
    }

    @Bean
    ManageDeliveryUseCase manageDelivery(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, IdGenerator ids, Clock clock) {
        return new ManageDeliveryUseCase(unitOfWork, deliveries, ids, clock);
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
        return meters.timer(MetricNames.in("booking", MetricNames.WEBHOOK_LAG))::record;
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
     * The ten schemas the build packages from {@code contracts/events/booking}, read back off the classpath: the
     * catalog cannot drift from the contract, and a new schema needs no code change. An empty set means the packaging
     * broke, and a Tenant could then subscribe to nothing — a startup failure rather than a silent no-op.
     */
    private static Set<String> packagedEventTypes() {
        String suffix = ".schema.json";
        Set<String> types = new TreeSet<>();
        try {
            for (Resource schema : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:events/*" + suffix)) {
                String name = schema.getFilename();
                if (name != null && name.endsWith(suffix)) {
                    types.add(name.substring(0, name.length() - suffix.length()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the packaged booking event schemas", e);
        }
        if (types.isEmpty()) {
            throw new IllegalStateException(
                    "no booking event schema is packaged: contracts/events/booking must be on the classpath under events/");
        }
        return Set.copyOf(types);
    }

    /** The catalog port over the packaged schemas. */
    record BookingEventTypes(Set<String> eventTypes) implements EventTypeCatalog {}
}
