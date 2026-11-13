package dev.ngb.billing.invoicing.application.cycle.command;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.invoicing.application.cycle.port.UnbilledLineRepository;
import dev.ngb.billing.invoicing.application.cycle.service.CycleSettings;
import dev.ngb.billing.invoicing.application.invoice.service.ChargeLineFactory;
import dev.ngb.billing.invoicing.application.messaging.port.MalformedEventRecorder;
import dev.ngb.billing.invoicing.application.messaging.service.InvoicingConsumers;
import dev.ngb.billing.invoicing.domain.cycle.UnbilledLine;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceItem;
import dev.ngb.billing.money.api.event.ChargeSucceededPayload;
import dev.ngb.billing.money.api.event.MoneyEventTypes;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumes {@code charge.succeeded}: a charge on a Postpaid wallet becomes an unbilled line of the calendar period
 * it was incurred in. A charge on a Prepaid wallet is ignored, because the wallet already paid it. The inbox and the
 * unique line per charge make a repeated event a no-op.
 *
 * <p>A payload that is not a charge event is a malformed event. The policy is to record it once through
 * {@link MalformedEventRecorder} (a WARN line and a counter) and treat it as handled: the inbox mark this consumer
 * already wrote stands, and so does the rest of the transaction, which the other consumers of the same event share
 * (the Tenant's webhook delivery among them). Failing here instead would retry an event that can never decode, and
 * would hold back the delivery that shares its transaction.
 */
public class CollectChargeUseCase {

    private final UnitOfWork unitOfWork;
    private final EventInbox inbox;
    private final WalletLookup wallets;
    private final UnbilledLineRepository lines;
    private final ChargeLineFactory lineFactory;
    private final CycleSettings settings;
    private final Clock clock;
    private final MalformedEventRecorder malformedEvents;

    public CollectChargeUseCase(
            UnitOfWork unitOfWork, EventInbox inbox, WalletLookup wallets, UnbilledLineRepository lines, ChargeLineFactory lineFactory, CycleSettings settings,
            Clock clock, MalformedEventRecorder malformedEvents) {
        this.unitOfWork = unitOfWork;
        this.inbox = inbox;
        this.wallets = wallets;
        this.lines = lines;
        this.lineFactory = lineFactory;
        this.settings = settings;
        this.clock = clock;
        this.malformedEvents = malformedEvents;
    }

    /** @return true when a line was added. */
    public boolean handle(DomainEvent event) {
        if (!MoneyEventTypes.CHARGE_SUCCEEDED.equals(event.type())) {
            return false;
        }
        AtomicReference<MalformedEventException> malformed = new AtomicReference<>();
        boolean added = unitOfWork.transactional(() -> {
            if (!inbox.firstTime(InvoicingConsumers.CYCLE, event)) {
                return false;
            }
            ChargeSucceededPayload charged;
            try {
                charged = ChargeSucceededPayload.parse(event.payload());
            } catch (MalformedEventException broken) {
                // Treated as handled: the inbox mark this transaction wrote stands, so the event is not retried.
                malformed.set(broken);
                return false;
            }
            WalletSummary wallet = wallets.find(charged.walletId()).orElse(null);
            if (wallet == null || wallet.mode() != WalletMode.POSTPAID) {
                return false;
            }
            if (!charged.amount().currency().equals(wallet.currency())) {
                throw new IllegalStateException("a charge is in the currency of its wallet");
            }
            Instant now = clock.now();
            Instant start = settings.cycle().calendarStart(event.occurredAt());
            if (lines.periodInvoiced(wallet.id(), start)) {
                // The period was billed before this event reached us; the line belongs to the period that is open now.
                start = settings.cycle().calendarStart(now);
            }
            InvoiceItem item = lineFactory.lineFor(charged.chargeId(), charged.source(), charged.reference(), charged.amount(), event.occurredAt());
            return lines.add(new UnbilledLine(event.tenantId(), wallet.customerId(), wallet.id(), item, start, settings.cycle().calendarEnd(start)));
        });
        if (malformed.get() != null) {
            // Reported after the transaction, so the WARN line and the counter describe a drop that did happen.
            malformedEvents.record(InvoicingConsumers.CYCLE, event, malformed.get());
        }
        return added;
    }
}
