package dev.ngb.mailer.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.boot.config.MailerSettings.Delivery;
import dev.ngb.mailer.email.adapter.credential.aws.ConfiguredSenderResolver;
import dev.ngb.mailer.email.adapter.credential.aws.CredentialProviderCache;
import dev.ngb.mailer.email.adapter.credential.aws.SesCredentialProbe;
import dev.ngb.mailer.email.adapter.credential.persistence.JooqCredentialStore;
import dev.ngb.mailer.email.adapter.delivery.messaging.OutboxDeliveryResultPublisher;
import dev.ngb.mailer.email.adapter.delivery.persistence.JooqDeliveryClaimRepository;
import dev.ngb.mailer.email.adapter.delivery.persistence.JooqEmailDeliveryRepository;
import dev.ngb.mailer.email.adapter.delivery.ses.SesClientFactory;
import dev.ngb.mailer.email.adapter.delivery.ses.SesV2EmailSender;
import dev.ngb.mailer.email.adapter.delivery.smtp.SmtpEmailSender;
import dev.ngb.mailer.email.adapter.email.persistence.JooqMailerTenants;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import dev.ngb.mailer.email.application.email.command.CredentialHealthUseCase;
import dev.ngb.mailer.email.application.email.command.DeliverEmailsUseCase;
import dev.ngb.mailer.email.application.email.command.HousekeepingUseCase;
import dev.ngb.mailer.email.application.email.port.CredentialProbe;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.DeliveryClaimRepository;
import dev.ngb.mailer.email.application.email.port.DeliveryResultPublisher;
import dev.ngb.mailer.email.application.email.port.EmailDeliveryRepository;
import dev.ngb.mailer.email.application.email.port.EmailSender;
import dev.ngb.mailer.email.application.email.port.MailerTenants;
import dev.ngb.mailer.email.application.email.port.SenderResolver;
import dev.ngb.mailer.email.application.email.service.DeliverySettings;
import dev.ngb.mailer.email.application.email.service.HousekeepingSettings;
import dev.ngb.mailer.email.domain.delivery.DeliveryPolicy;
import dev.ngb.core.util.Backoff;
import java.security.SecureRandom;
import java.util.function.DoubleSupplier;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wires the email context: its ports to their adapters, and the transport selected by {@code mailer.sender.kind}.
 *
 * <p>The two senders are mutually exclusive beans rather than a factory choosing by string at every call: the choice is
 * made once, at startup, and the rest of the service only ever sees {@link EmailSender}.
 */
@Configuration(proxyBeanMethods = false)
class EmailConfiguration {

    @Bean
    EmailDeliveryRepository emailDeliveryRepository(DSLContext dsl, JsonMapper json) {
        return new JooqEmailDeliveryRepository(dsl, json);
    }

    @Bean
    DeliveryClaimRepository deliveryClaimRepository(DSLContext dsl, JsonMapper json) {
        return new JooqDeliveryClaimRepository(dsl, json);
    }

    @Bean
    DeliveryResultPublisher deliveryResultPublisher(EventPublisher outbox, IdGenerator ids) {
        return new OutboxDeliveryResultPublisher(outbox, ids);
    }

    @Bean
    MailerTenants mailerTenants(UnitOfWork unitOfWork, DSLContext dsl) {
        return new JooqMailerTenants(unitOfWork, dsl);
    }

    @Bean
    CredentialStore credentialStore(DSLContext dsl) {
        return new JooqCredentialStore(dsl);
    }

    @Bean
    CredentialProviderCache credentialProviderCache(MailerSettings settings) {
        return new CredentialProviderCache(settings.credentials().cacheSize());
    }

    @Bean
    SenderResolver senderResolver(
            CredentialStore credentials,
            SecretCipher cipher,
            CredentialProviderCache providers,
            MailerSettings settings) {
        return new ConfiguredSenderResolver(
                credentials,
                cipher,
                providers,
                settings.sender().region(),
                settings.sender().defaultFromAddress(),
                settings.sender().defaultFromName());
    }

    @Bean
    @ConditionalOnProperty(name = "mailer.sender.kind", havingValue = "ses")
    SesClientFactory sesClientFactory(MailerSettings settings) {
        return new SesClientFactory(settings.ses().endpointOverride());
    }

    @Bean
    @ConditionalOnProperty(name = "mailer.sender.kind", havingValue = "ses")
    EmailSender sesEmailSender(SesClientFactory clients) {
        return new SesV2EmailSender(clients);
    }

    @Bean
    @ConditionalOnProperty(name = "mailer.sender.kind", havingValue = "smtp", matchIfMissing = true)
    EmailSender smtpEmailSender(JavaMailSender mail) {
        return new SmtpEmailSender(mail);
    }

    @Bean
    CredentialProbe credentialProbe() {
        return new SesCredentialProbe();
    }

    @Bean
    DeliveryPolicy deliveryPolicy(MailerSettings settings) {
        Delivery delivery = settings.delivery();
        // The construction-time check is the invariant: a lease shorter than the worst-case send lets a second worker
        // claim a delivery whose first send is still in flight.
        return new DeliveryPolicy(
                delivery.maxAttempts(),
                new Backoff(delivery.backoffBase(), delivery.backoffCap(), delivery.jitter()),
                delivery.lease(),
                delivery.sendDeadline());
    }

    @Bean
    DeliverySettings deliverySettings(MailerSettings settings, DeliveryPolicy policy) {
        Delivery delivery = settings.delivery();
        return new DeliverySettings(delivery.enabled(), delivery.batchSize(), delivery.pollDelay(), policy);
    }

    @Bean
    HousekeepingSettings housekeepingSettings(MailerSettings settings) {
        MailerSettings.Housekeeping housekeeping = settings.housekeeping();
        return new HousekeepingSettings(
                housekeeping.enabled(),
                housekeeping.bodyRetention(),
                housekeeping.inboxRetention(),
                housekeeping.batchSize(),
                housekeeping.interval());
    }

    @Bean
    AcceptEmailCommandUseCase acceptEmailCommandUseCase(
            UnitOfWork unitOfWork,
            EmailDeliveryRepository deliveries,
            MailerTenants tenants,
            Clock clock,
            IdGenerator ids) {
        return new AcceptEmailCommandUseCase(unitOfWork, deliveries, tenants, clock, ids);
    }

    @Bean
    DoubleSupplier mailerRandom() {
        SecureRandom random = new SecureRandom();
        return random::nextDouble;
    }

    @Bean
    DeliverEmailsUseCase deliverEmailsUseCase(
            UnitOfWork unitOfWork,
            DeliveryClaimRepository claims,
            SenderResolver resolver,
            EmailSender sender,
            DeliveryResultPublisher results,
            TenantContext tenantContext,
            DeliverySettings settings,
            Clock clock,
            DoubleSupplier random) {
        return new DeliverEmailsUseCase(
                unitOfWork, claims, resolver, sender, results, tenantContext, settings, clock, random);
    }

    @Bean
    HousekeepingUseCase housekeepingUseCase(
            UnitOfWork unitOfWork,
            DeliveryClaimRepository claims,
            HousekeepingSettings settings,
            Clock clock) {
        return new HousekeepingUseCase(unitOfWork, claims, settings, clock);
    }

    @Bean
    CredentialHealthUseCase credentialHealthUseCase(
            UnitOfWork unitOfWork, CredentialStore credentials, CredentialProbe probe, SecretCipher cipher, Clock clock) {
        return new CredentialHealthUseCase(unitOfWork, credentials, probe, cipher, clock);
    }
}
