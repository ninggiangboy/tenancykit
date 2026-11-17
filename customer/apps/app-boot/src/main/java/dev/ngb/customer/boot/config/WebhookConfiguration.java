package dev.ngb.customer.boot.config;

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
import dev.ngb.customer.webhook.adapter.delivery.crypto.SharedPayloadSigner;
import dev.ngb.customer.webhook.adapter.delivery.http.SharedWebhookSender;
import dev.ngb.customer.webhook.adapter.delivery.json.JacksonEventBodyWriter;
import dev.ngb.customer.webhook.adapter.delivery.messaging.WebhookEventListener;
import dev.ngb.customer.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.customer.webhook.adapter.delivery.persistence.JooqWebhookDeliveryRepository;
import dev.ngb.customer.webhook.adapter.endpoint.crypto.RandomSigningKeys;
import dev.ngb.customer.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.customer.webhook.adapter.endpoint.persistence.JooqWebhookEndpointRepository;
import dev.ngb.customer.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.customer.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.customer.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import dev.ngb.customer.webhook.application.delivery.command.RecordDeliveriesUseCase;
import dev.ngb.customer.webhook.application.delivery.port.DeliveryLagRecorder;
import dev.ngb.customer.webhook.application.delivery.port.EventBodyWriter;
import dev.ngb.customer.webhook.application.delivery.port.PayloadSigner;
import dev.ngb.customer.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.customer.webhook.application.delivery.port.WebhookSender;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.customer.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import dev.ngb.customer.webhook.application.endpoint.command.ManageEndpointUseCase;
import dev.ngb.customer.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.customer.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.customer.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.customer.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.customer.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.customer.webhook.application.endpoint.service.WebhookSettings;
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
 * Wires the webhook context. It holds no business rules: the mechanics — signing, the destination policy, the DNS
 * guard, the sender and the retry schedule — live in {@code core-webhook}, and the delivery state lives in the webhook
 * context.
 *
 * <p>The outbox relay is wired in {@code OutboxConfiguration}; the listener below is what it publishes to, inside the
 * relay's own transaction, so a delivery row is committed with the event and nothing is queued for a rolled-back change.
 */
@Configuration(proxyBeanMethods = false)
class WebhookConfiguration {

    @Bean
    WebhookSettings webhookSettings(CustomerSettings settings) {
        CustomerSettings.Webhooks webhooks = settings.webhooks();
        return new WebhookSettings(
                webhooks.allowNonPublicTargets(),
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
    AesGcmSecretCipher secretCipher(CustomerSettings settings) {
        return new AesGcmSecretCipher(
                "customer.webhooks", settings.webhooks().masterKey(), settings.webhooks().masterKeyId());
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
    SharedWebhookSender webhookSender(WebhookSettings settings, CustomerSettings customer) {
        CustomerSettings.Webhooks webhooks = customer.webhooks();
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

    /** The catalog is the packaged event schemas themselves, so a type customer does not produce cannot be subscribed to. */
    @Bean
    EventTypeCatalog eventTypeCatalog() {
        return new CrmEventTypes(packagedEventTypes());
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
    PurgeDeliveriesUseCase purgeDeliveries(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, CustomerSettings settings) {
        return new PurgeDeliveriesUseCase(
                unitOfWork,
                deliveries,
                settings.housekeeping().webhookDeliveryRetention(),
                settings.housekeeping().batchSize());
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
    DeliveryLagRecorder deliveryLagRecorder(io.micrometer.core.instrument.MeterRegistry meters) {
        return meters.timer(MetricNames.in("customer", MetricNames.WEBHOOK_LAG))::record;
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
     * The four schemas the build packages from {@code contracts/events/customer}, read back off the classpath: the catalog
     * cannot drift from the contract, and a new schema needs no code change. An empty set means the packaging broke,
     * and a Tenant could then subscribe to nothing — a startup failure rather than a silent no-op.
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
            throw new IllegalStateException("could not read the packaged customer event schemas", e);
        }
        if (types.isEmpty()) {
            throw new IllegalStateException(
                    "no customer event schema is packaged: contracts/events/customer must be on the classpath under events/");
        }
        return Set.copyOf(types);
    }

    /** The catalog port over the packaged schemas. */
    record CrmEventTypes(Set<String> eventTypes) implements EventTypeCatalog {}
}
