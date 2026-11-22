package dev.ngb.billing.invoicing.application.invoice.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.invoicing.application.invoice.port.InvoiceRepository;
import dev.ngb.billing.invoicing.application.messaging.port.MalformedEventRecorder;
import dev.ngb.billing.invoicing.application.messaging.service.InvoicingActors;
import dev.ngb.billing.invoicing.application.messaging.service.InvoicingConsumers;
import dev.ngb.billing.invoicing.domain.invoice.Invoice;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceEvents;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceId;
import dev.ngb.billing.money.api.ledger.LedgerPostingResult;
import dev.ngb.billing.money.api.wallet.CreditCommand;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.payment.api.payment.PaymentEventTypes;
import dev.ngb.billing.payment.api.payment.PaymentSucceededPayload;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumes the verified payment results of invoice payments ({@code payment.succeeded} and {@code payment.failed}
 * that carry an {@code invoiceId}; top-ups carry none and are ignored). On success the debt payoff is credited to
 * the wallet through the money context, the invoice is marked paid and {@code invoice.paid} is announced, all in
 * the relay's transaction together with the inbox mark, so a repeated event changes nothing. The credit key is
 * derived from the payment, so even a lost inbox mark could not credit twice.
 *
 * <p>Money that arrives for an invoice that cannot be paid any more (already paid by another payment, or void) is
 * still credited to the wallet, because the customer really paid it; the payment is recorded in the invoice history.
 *
 * <p>A payload that does not have the shape of a payment result is a malformed event: it is recorded once through
 * {@link MalformedEventRecorder} and treated as handled, exactly as {@code CollectChargeUseCase} does with a
 * malformed charge (see there for why the transaction is not failed).
 */
public class ApplyPaymentUseCase {

    private final UnitOfWork unitOfWork;
    private final EventInbox inbox;
    private final InvoiceRepository invoices;
    private final CreditWallet credit;
    private final EventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;
    private final MalformedEventRecorder malformedEvents;

    public ApplyPaymentUseCase(
            UnitOfWork unitOfWork, EventInbox inbox, InvoiceRepository invoices, CreditWallet credit, EventPublisher events, IdGenerator ids, Clock clock,
            MalformedEventRecorder malformedEvents) {
        this.unitOfWork = unitOfWork;
        this.inbox = inbox;
        this.invoices = invoices;
        this.credit = credit;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.malformedEvents = malformedEvents;
    }

    /** @return true when the invoice was changed */
    public boolean handle(DomainEvent event) {
        if (!PaymentEventTypes.PAYMENT_SUCCEEDED.equals(event.type()) && !PaymentEventTypes.PAYMENT_FAILED.equals(event.type())) {
            return false;
        }
        Map<String, Object> data = event.payload();
        if (data.get("invoiceId") == null) {
            return false;
        }
        AtomicReference<MalformedEventException> malformed = new AtomicReference<>();
        boolean applied = unitOfWork.transactional(() -> {
            if (!inbox.firstTime(InvoicingConsumers.PAYMENT, event)) {
                return false;
            }
            PaymentSucceededPayload payment;
            try {
                payment = PaymentSucceededPayload.parse(data);
            } catch (MalformedEventException broken) {
                // Treated as handled: the inbox mark this transaction wrote stands, so the event is not retried.
                malformed.set(broken);
                return false;
            }
            Invoice invoice = invoices.lockById(new InvoiceId(payment.invoiceId()))
                    .orElseThrow(() -> new NotFoundException("invoice " + payment.invoiceId() + " of a payment was not found"));
            Instant now = clock.now();
            if (PaymentEventTypes.PAYMENT_FAILED.equals(event.type())) {
                Invoice.Change change = invoice.recordPaymentFailed(payment.paymentId(), payment.reason(), now);
                if (change != Invoice.Change.UNCHANGED) {
                    invoices.update(invoice);
                }
                return change != Invoice.Change.UNCHANGED;
            }
            if (!payment.amount().currency().equals(invoice.currency())) {
                throw new IllegalStateException(
                        "a payment of invoice %s is in %s but the invoice is in %s".formatted(invoice.id(), payment.amount().currency(), invoice.currency()));
            }
            LedgerPostingResult posted = credit.credit(new CreditCommand(invoice.walletId(), payment.amount(), AggregateTargets.invoice(invoice.id()),
                    "Payment of invoice " + invoice.number(), "invoice-payoff:" + payment.paymentId(), InvoicingActors.INVOICE_PAYMENT));
            Invoice.Change change = invoice.recordPaymentSucceeded(payment.paymentId(), posted.entryId().value(), now);
            if (change == Invoice.Change.UNCHANGED) {
                return false;
            }
            invoices.update(invoice);
            if (change == Invoice.Change.PAID) {
                events.publish(new InvoiceEvents.Paid(ids.newId(), invoice, payment.paymentId(), now));
            }
            return true;
        });
        if (malformed.get() != null) {
            // Reported after the transaction, so the WARN line and the counter describe a drop that did happen.
            malformedEvents.record(InvoicingConsumers.PAYMENT, event, malformed.get());
        }
        return applied;
    }
}
