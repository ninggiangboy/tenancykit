package dev.ngb.billing.invoicing.application.invoice.service;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditPreamble;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.billing.invoicing.application.cycle.port.UnbilledLineRepository;
import dev.ngb.billing.invoicing.application.invoice.port.InvoiceRepository;
import dev.ngb.billing.invoicing.domain.cycle.UnbilledLine;
import dev.ngb.billing.invoicing.domain.invoice.Invoice;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceId;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceItem;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceOrigin;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.ChargeSummary;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a succeeded charge becomes an invoice of its own: a line already waiting for its cycle moves onto
 * it, a Postpaid charge opens for payment and a Prepaid one is a paid record. It must run inside the caller's
 * transaction, which has already established that the caller may see the charge.
 */
public class ChargeInvoiceIssuer {

    /** The invoice a charge was billed on, and whether it already existed when the request arrived. */
    public record Issued(Invoice invoice, boolean replayed) {}

    private final InvoiceRepository invoices;
    private final UnbilledLineRepository unbilled;
    private final InvoiceIssuer issuer;
    private final ChargeLineFactory lineFactory;
    private final AuditPreamble audit;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ChargeInvoiceIssuer(
            InvoiceRepository invoices, UnbilledLineRepository unbilled, InvoiceIssuer issuer, ChargeLineFactory lineFactory, AuditPreamble audit,
            TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.unbilled = Objects.requireNonNull(unbilled, "unbilled");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.lineFactory = Objects.requireNonNull(lineFactory, "lineFactory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @throws ConflictException when the charge failed, so it cannot be billed */
    public Issued issue(ChargeSummary charge, WalletSummary wallet, Audit caller) {
        Optional<Invoice> existing = invoices.findByCharge(charge.id().value());
        if (existing.isPresent()) {
            return new Issued(existing.get(), true);
        }
        if (charge.status() != ChargeStatus.SUCCEEDED) {
            throw new ConflictException("a failed charge cannot be invoiced");
        }
        Instant now = clock.now();
        Optional<UnbilledLine> waiting = unbilled.lockByCharge(charge.id().value());
        InvoiceItem item = waiting.map(UnbilledLine::item).orElseGet(() -> lineFactory.lineFor(charge.id().value(), charge.source().name(),
                charge.reference(), charge.amount(), charge.createdAt()));
        Invoice draft = Invoice.draft(new InvoiceId(ids.newId()), tenantContext.requireTenant(), wallet.customerId(), wallet.id(), wallet.currency(),
                InvoiceOrigin.CHARGE, List.of(item), null, null, now);
        Invoice issued = issuer.issue(draft, wallet.mode() == WalletMode.PREPAID);
        audit.record(caller, "invoice.issued-for-charge", AggregateTargets.invoice(issued.id()), now);
        return new Issued(issued, false);
    }
}
