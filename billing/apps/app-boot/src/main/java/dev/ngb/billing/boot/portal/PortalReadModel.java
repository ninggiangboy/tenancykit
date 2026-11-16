package dev.ngb.billing.boot.portal;

import dev.ngb.billing.customer.application.customer.query.CustomerQueries;
import dev.ngb.billing.customer.application.customer.query.CustomerView;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceQueries;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceView;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionQueries;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionView;
import dev.ngb.billing.money.application.charge.query.ChargeView;
import dev.ngb.billing.money.application.ledger.query.LedgerEntryView;
import dev.ngb.billing.money.application.wallet.query.WalletQueries;
import dev.ngb.billing.money.application.wallet.query.WalletView;
import dev.ngb.billing.payment.application.payment.command.CreateTopUpUseCase;
import dev.ngb.billing.payment.application.payment.command.OpenPaymentMethodsUseCase;
import dev.ngb.billing.payment.application.payment.query.PaymentQueries;
import dev.ngb.billing.payment.application.payment.query.PaymentView;
import dev.ngb.billing.payment.application.refund.query.RefundQueries;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.session.api.portal.CurrentPortalVisitor;
import dev.ngb.billing.session.api.portal.PortalVisitor;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.usage.api.lookup.UsageEventId;
import dev.ngb.billing.usage.api.lookup.UsageTotal;
import dev.ngb.billing.usage.application.usage.query.UsageEventView;
import dev.ngb.billing.usage.application.usage.query.UsageQueries;
import java.time.Instant;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The Customer Portal's read and pay composition. Every method takes the customer from the portal principal,
 * never from a path or body value, and a resource that belongs to another customer answers 404 exactly like a
 * resource that does not exist.
 */
@Profile("api")
@ConditionalOnProperty(name = "billing.portal.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class PortalReadModel {

    private final CurrentPortalVisitor visitor;
    private final CustomerQueries customers;
    private final WalletQueries wallets;
    private final UsageQueries usage;
    private final InvoiceQueries invoices;
    private final SubscriptionQueries subscriptions;
    private final PaymentQueries payments;
    private final RefundQueries refunds;
    private final CreateTopUpUseCase topUps;
    private final OpenPaymentMethodsUseCase paymentMethods;

    public PortalReadModel(
            CurrentPortalVisitor visitor, CustomerQueries customers, WalletQueries wallets, UsageQueries usage, InvoiceQueries invoices,
            SubscriptionQueries subscriptions, PaymentQueries payments, RefundQueries refunds, CreateTopUpUseCase topUps,
            OpenPaymentMethodsUseCase paymentMethods) {
        this.visitor = visitor;
        this.customers = customers;
        this.wallets = wallets;
        this.usage = usage;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.refunds = refunds;
        this.topUps = topUps;
        this.paymentMethods = paymentMethods;
    }

    public PortalVisitor principal() {
        return visitor.get();
    }

    public CustomerView customer() {
        return customers.get(customerId());
    }

    public Page<WalletView> wallets(ListQuery query) {
        return wallets.walletsForCustomer(customerId(), query);
    }

    public WalletView wallet(String walletId) {
        WalletView wallet = wallets.wallet(WalletId.of(walletId));
        requireOwned(wallet.customerId(), "wallet not found");
        return wallet;
    }

    public Page<LedgerEntryView> ledger(String walletId, ListQuery query) {
        wallet(walletId);
        return wallets.ledger(WalletId.of(walletId), query);
    }

    public Page<ChargeView> charges(ListQuery query) {
        return wallets.chargesForCustomer(customerId(), query);
    }

    public Page<UsageEventView> usage(ListQuery query) {
        return usage.historyForCustomer(customerId(), query);
    }

    public List<UsageTotal> usageTotals(String metric, Instant from, Instant to) {
        return usage.totals(customerId(), metric == null ? Set.of() : Set.of(metric), from, to, null);
    }

    public Page<InvoiceView> invoices(ListQuery query) {
        return invoices.listForCustomer(customerId(), query);
    }

    public InvoiceQueries.InvoiceDetail invoice(UUID id) {
        InvoiceQueries.InvoiceDetail detail = invoices.get(id, null);
        requireOwned(detail.invoice().customerId(), "invoice not found");
        return detail;
    }

    public Page<SubscriptionView> subscriptions(ListQuery query) {
        return subscriptions.listForCustomer(customerId(), query);
    }

    public SubscriptionQueries.SubscriptionDetail subscription(UUID id) {
        SubscriptionQueries.SubscriptionDetail detail = subscriptions.get(id, null);
        requireOwned(detail.subscription().customerId(), "subscription not found");
        return detail;
    }

    public Page<PaymentView> payments(ListQuery query) {
        return payments.listForCustomer(customerId(), query);
    }

    public PaymentQueries.PaymentDetail payment(UUID id) {
        return payments.paymentForCustomer(id, customerId());
    }

    public Page<RefundView> refunds(ListQuery query) {
        return refunds.listForCustomer(customerId(), query);
    }

    public CreateTopUpUseCase.Result topUp(String walletId, Money amount, String idempotencyKey, String requestId) {
        WalletView wallet = wallet(walletId);
        String usableRequestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        return topUps.execute(new CreateTopUpUseCase.Command(
                wallet.id(), amount, idempotencyKey, new Audit("portal-session/" + principal().sessionId(), usableRequestId)));
    }

    public String paymentMethodsLink(String returnUrl) {
        return paymentMethods.execute(new OpenPaymentMethodsUseCase.Command(customerId(), returnUrl)).url();
    }

    private CustomerId customerId() {
        return visitor.get().customerId();
    }

    private void requireOwned(CustomerId owner, String notFound) {
        if (!owner.equals(customerId())) {
            throw new NotFoundException(notFound);
        }
    }
}
