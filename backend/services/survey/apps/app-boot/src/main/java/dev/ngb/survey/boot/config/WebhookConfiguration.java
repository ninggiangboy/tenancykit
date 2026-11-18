package dev.ngb.survey.boot.config;

import dev.ngb.core.event.EventInbox;
import dev.ngb.core.event.SuiteEventTypes;
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
import dev.ngb.survey.webhook.adapter.delivery.crypto.SharedPayloadSigner;
import dev.ngb.survey.webhook.adapter.delivery.http.SharedWebhookSender;
import dev.ngb.survey.webhook.adapter.delivery.json.JacksonEventBodyWriter;
import dev.ngb.survey.webhook.adapter.delivery.messaging.WebhookEventListener;
import dev.ngb.survey.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.survey.webhook.adapter.delivery.persistence.JooqWebhookDeliveryRepository;
import dev.ngb.survey.webhook.adapter.endpoint.crypto.RandomSigningKeys;
import dev.ngb.survey.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.survey.webhook.adapter.endpoint.persistence.JooqWebhookEndpointRepository;
import dev.ngb.survey.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.survey.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.survey.webhook.application.delivery.command.RecordDeliveriesUseCase;
import dev.ngb.survey.webhook.application.delivery.port.DeliveryLagRecorder;
import dev.ngb.survey.webhook.application.delivery.port.EventBodyWriter;
import dev.ngb.survey.webhook.application.delivery.port.PayloadSigner;
import dev.ngb.survey.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.survey.webhook.application.delivery.port.WebhookSender;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryReadRepository;
import dev.ngb.survey.webhook.application.endpoint.command.CreateEndpointUseCase;
import dev.ngb.survey.webhook.application.endpoint.command.ManageEndpointUseCase;
import dev.ngb.survey.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.survey.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.survey.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.survey.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.survey.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.survey.webhook.application.endpoint.service.WebhookSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbox relay and the webhook module. It holds no business rules: the mechanics live in core-webhook and
 * the delivery state in the webhook context.
 *
 * <p>The relay publishes what the outbox holds; the webhook module listens to the same events in the same transaction
 * and queues a delivery per subscribed endpoint, which the worker then claims and sends.
 */
@Configuration(proxyBeanMethods = false)
class WebhookConfiguration {

    @Bean
    OutboxRelay outboxRelay(
            UnitOfWork unitOfWork,
            DSLContext dsl,
            JsonMapper json,
            ApplicationEventPublisher publisher,
            Clock clock,
            MeterRegistry meters,
            SurveySettings settings) {
        SurveySettings.Relay relay = settings.relay();
        return new OutboxRelay(
                unitOfWork,
                dsl,
                json,
                publisher,
                clock,
                meters,
                "survey",
                relay.batchSize(),
                relay.maxAttempts(),
                new Backoff(relay.backoffBase(), relay.backoffCap(), relay.jitter()));
    }

    @Bean
    WebhookSettings webhookSettings(SurveySettings settings) {
        SurveySettings.Relay relay = settings.relay();
        return new WebhookSettings(
                settings.webhooks().allowInsecureTargets(),
                settings.webhooks().batchSize(),
                new RetrySchedule(
                        settings.webhooks().maxAttempts(),
                        new Backoff(
                                settings.webhooks().backoffBase(),
                                settings.webhooks().backoffCap(),
                                0.2),
                        settings.webhooks().lease()));
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
    AesGcmSecretCipher secretCipher(SurveySettings settings) {
        return new AesGcmSecretCipher(
                "survey.secrets",
                settings.webhooks().masterKey(),
                settings.webhooks().masterKeyId());
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
    SharedWebhookSender webhookSender(WebhookSettings settings, SurveySettings survey) {
        var webhooks = survey.webhooks();
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

    /** The catalog is the survey event schemas themselves, so a type the app does not produce cannot be subscribed to. */
    @Bean
    EventTypeCatalog eventTypeCatalog() {
        Set<String> types = SuiteEventTypes.survey();
        return () -> types;
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
        return meters.timer(MetricNames.in("survey", MetricNames.WEBHOOK_LAG))::record;
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
}
