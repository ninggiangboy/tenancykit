package dev.ngb.billing.boot.config;

import dev.ngb.core.observability.MetricNames;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Backoff;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.invoicing.adapter.cycle.persistence.JooqUnbilledLineRepository;
import dev.ngb.billing.invoicing.adapter.invoice.messaging.InvoicingEventListener;
import dev.ngb.billing.invoicing.adapter.invoice.persistence.JooqInvoiceReadRepository;
import dev.ngb.billing.invoicing.adapter.invoice.persistence.JooqInvoiceRepository;
import dev.ngb.billing.invoicing.adapter.subscription.persistence.JooqSubscriptionReadRepository;
import dev.ngb.billing.invoicing.adapter.subscription.persistence.JooqSubscriptionRepository;
import dev.ngb.billing.invoicing.application.cycle.command.CloseBillingCycleUseCase;
import dev.ngb.billing.invoicing.application.cycle.command.CollectChargeUseCase;
import dev.ngb.billing.invoicing.application.cycle.port.CycleFailureRecorder;
import dev.ngb.billing.invoicing.application.cycle.port.UnbilledLineRepository;
import dev.ngb.billing.invoicing.application.cycle.service.CycleSettings;
import dev.ngb.billing.invoicing.application.invoice.command.ApplyPaymentUseCase;
import dev.ngb.billing.invoicing.application.invoice.command.InvoiceChargeUseCase;
import dev.ngb.billing.invoicing.application.invoice.command.ManageInvoiceUseCase;
import dev.ngb.billing.invoicing.application.invoice.command.PayInvoiceUseCase;
import dev.ngb.billing.invoicing.application.invoice.port.InvoiceRepository;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceQueries;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceReadRepository;
import dev.ngb.billing.invoicing.application.invoice.service.ChargeLineFactory;
import dev.ngb.billing.invoicing.application.invoice.service.InvoiceIssuer;
import dev.ngb.billing.invoicing.application.messaging.port.MalformedEventRecorder;
import dev.ngb.billing.invoicing.application.subscription.command.CancelSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.command.CreateSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.command.RenewSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.port.SubscriptionRepository;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionQueries;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionReadRepository;
import dev.ngb.billing.invoicing.application.subscription.service.RenewalSettings;
import dev.ngb.billing.money.api.charge.ChargeLookup;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.payment.api.invoice.StartInvoicePayment;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.pricing.api.plan.PlanPricing;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the invoicing module. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class InvoicingConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(InvoicingConfiguration.class);

    @Bean
    InvoiceRepository invoiceRepository(DSLContext dsl) {
        return new JooqInvoiceRepository(dsl);
    }

    @Bean
    InvoiceIssuer invoiceIssuer(InvoiceRepository invoices, EventPublisher events, IdGenerator ids, Clock clock) {
        return new InvoiceIssuer(invoices, events, ids, clock);
    }

    @Bean
    ChargeLineFactory chargeLineFactory(SubscriptionRepository subscriptions, IdGenerator ids) {
        return new ChargeLineFactory(subscriptions, ids);
    }

    @Bean
    CycleSettings cycleSettings(
            @Value("${billing.invoicing.postpaid-cycle:MONTHLY}") BillingCycle cycle, @Value("${billing.invoicing.cycle-batch:100}") int batchSize) {
        return new CycleSettings(cycle, batchSize);
    }

    @Bean
    UnbilledLineRepository unbilledLineRepository(DSLContext dsl) {
        return new JooqUnbilledLineRepository(dsl);
    }

    /**
     * The application layer has no logger, so the two ways a consumer reports something it could not act on live
     * here: an event whose payload it cannot decode, and a billing period it cannot close. Both are WARN lines with
     * the ids the failure is about, and a counter the alert listens on.
     */
    @Bean
    MalformedEventRecorder malformedEventRecorder(MeterRegistry meters) {
        return (consumer, event, malformed) -> {
            LOG.warn("dropping event {} of type {} for {}: {}", event.eventId(), event.type(), consumer, malformed.getMessage());
            meters.counter(MetricNames.in("billing", MetricNames.EVENTS_MALFORMED), "consumer", consumer).increment();
        };
    }

    @Bean
    CycleFailureRecorder cycleFailureRecorder() {
        return (walletId, cycleStart, cycleEnd, cause) -> LOG.warn(
                "the billing period {} to {} of wallet {} could not be closed", cycleStart, cycleEnd, walletId, cause);
    }

    @Bean
    CollectChargeUseCase collectChargeUseCase(
            UnitOfWork unitOfWork, EventInbox inbox, WalletLookup wallets, UnbilledLineRepository lines, ChargeLineFactory lineFactory, CycleSettings settings,
            Clock clock, MalformedEventRecorder malformedEvents) {
        return new CollectChargeUseCase(unitOfWork, inbox, wallets, lines, lineFactory, settings, clock, malformedEvents);
    }

    @Bean
    CloseBillingCycleUseCase closeBillingCycleUseCase(
            UnitOfWork unitOfWork, UnbilledLineRepository lines, InvoiceIssuer issuer, CycleSettings settings, IdGenerator ids, Clock clock,
            CycleFailureRecorder failures) {
        return new CloseBillingCycleUseCase(unitOfWork, lines, issuer, settings, ids, clock, failures);
    }

    @Bean
    PayInvoiceUseCase payInvoiceUseCase(
            UnitOfWork unitOfWork, InvoiceRepository invoices, CustomerLookup customers, StartInvoicePayment payments, AuditLog auditLog,
            TenantContext tenantContext, Clock clock) {
        return new PayInvoiceUseCase(unitOfWork, invoices, customers, payments, auditLog, tenantContext, clock);
    }

    @Bean
    InvoiceChargeUseCase invoiceChargeUseCase(
            UnitOfWork unitOfWork, ChargeLookup charges, WalletLookup wallets, CustomerLookup customers, InvoiceRepository invoices,
            UnbilledLineRepository unbilled, InvoiceIssuer issuer, ChargeLineFactory lineFactory, AuditLog auditLog, TenantContext tenantContext,
            IdGenerator ids, Clock clock) {
        return new InvoiceChargeUseCase(unitOfWork, charges, wallets, customers, invoices, unbilled, issuer, lineFactory, auditLog, tenantContext, ids, clock);
    }

    @Bean
    ApplyPaymentUseCase applyPaymentUseCase(
            UnitOfWork unitOfWork, EventInbox inbox, InvoiceRepository invoices, CreditWallet credit, EventPublisher events, IdGenerator ids, Clock clock,
            MalformedEventRecorder malformedEvents) {
        return new ApplyPaymentUseCase(unitOfWork, inbox, invoices, credit, events, ids, clock, malformedEvents);
    }

    @Bean
    InvoicingEventListener invoicingEventListener(CollectChargeUseCase collectCharge, ApplyPaymentUseCase applyPayment) {
        return new InvoicingEventListener(collectCharge, applyPayment);
    }

    @Bean
    SubscriptionRepository subscriptionRepository(DSLContext dsl) {
        return new JooqSubscriptionRepository(dsl);
    }

    @Bean
    CreateSubscriptionUseCase createSubscriptionUseCase(
            UnitOfWork unitOfWork, SubscriptionRepository subscriptions, WalletLookup wallets, CustomerLookup customers, PlanPricing plans,
            RenewSubscriptionUseCase renewal, AuditLog auditLog, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new CreateSubscriptionUseCase(unitOfWork, subscriptions, wallets, customers, plans, renewal, auditLog, tenantContext, ids, clock);
    }

    @Bean
    RenewalSettings renewalSettings(
            @Value("${billing.invoicing.renewal-batch:100}") int batchSize, @Value("${billing.invoicing.renewal-max-attempts:5}") int maxAttempts,
            @Value("${billing.invoicing.renewal-backoff-base:PT1H}") Duration base, @Value("${billing.invoicing.renewal-backoff-cap:P1D}") Duration cap) {
        return new RenewalSettings(batchSize, maxAttempts, new Backoff(base, cap, 0.2));
    }

    @Bean
    RenewSubscriptionUseCase renewSubscriptionUseCase(
            UnitOfWork unitOfWork, SubscriptionRepository subscriptions, PlanPricing plans, ChargeWallet money, EventPublisher events, RenewalSettings settings,
            IdGenerator ids, Clock clock) {
        return new RenewSubscriptionUseCase(unitOfWork, subscriptions, plans, money, events, settings, () -> ThreadLocalRandom.current().nextDouble(), ids, clock);
    }

    @Bean
    CancelSubscriptionUseCase cancelSubscriptionUseCase(
            UnitOfWork unitOfWork, SubscriptionRepository subscriptions, CustomerLookup customers, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new CancelSubscriptionUseCase(unitOfWork, subscriptions, customers, auditLog, ids, clock);
    }

    @Bean
    InvoiceReadRepository invoiceReadRepository(DSLContext dsl) {
        return new JooqInvoiceReadRepository(dsl);
    }

    @Bean
    SubscriptionReadRepository subscriptionReadRepository(DSLContext dsl) {
        return new JooqSubscriptionReadRepository(dsl);
    }

    @Bean
    InvoiceQueries invoiceQueries(UnitOfWork unitOfWork, InvoiceReadRepository invoices, CustomerLookup customers) {
        return new InvoiceQueries(unitOfWork, invoices, customers);
    }

    @Bean
    SubscriptionQueries subscriptionQueries(UnitOfWork unitOfWork, SubscriptionReadRepository subscriptions, CustomerLookup customers) {
        return new SubscriptionQueries(unitOfWork, subscriptions, customers);
    }

    @Bean
    ManageInvoiceUseCase manageInvoiceUseCase(UnitOfWork unitOfWork, InvoiceRepository invoices, AuditLog auditLog, Clock clock) {
        return new ManageInvoiceUseCase(unitOfWork, invoices, auditLog, clock);
    }
}
