package dev.ngb.billing.invoicing.application.invoice.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.invoicing.application.invoice.port.InvoiceRepository;
import dev.ngb.billing.invoicing.application.invoice.service.ChargeInvoiceIssuer;
import dev.ngb.billing.invoicing.domain.invoice.Invoice;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeLookup;
import dev.ngb.billing.money.api.charge.ChargeSummary;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import java.util.UUID;

/**
 * Issues an invoice for one succeeded charge, on a Tenant's request, with the same aggregate and numbering as a
 * cycle invoice. A charge is billed on one line at most, so asking again returns the invoice that already exists.
 * A Postpaid charge is still owed, so its invoice opens for payment (and a line already waiting for its cycle moves
 * onto it); a Prepaid charge was paid from the wallet when it was made, so its invoice is a paid record.
 */
public class InvoiceChargeUseCase {

    public record Command(ChargeId chargeId, Audit audit, UUID caller) {}

    /** {@code replayed} is true when the charge already had an invoice and that one is returned. */
    public record Result(Invoice invoice, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final ChargeLookup charges;
    private final WalletLookup wallets;
    private final CustomerVisibility visibility;
    private final InvoiceRepository invoices;
    private final ChargeInvoiceIssuer issuer;

    public InvoiceChargeUseCase(
            UnitOfWork unitOfWork, ChargeLookup charges, WalletLookup wallets, CustomerLookup customers, InvoiceRepository invoices,
            ChargeInvoiceIssuer issuer) {
        this.unitOfWork = unitOfWork;
        this.charges = charges;
        this.wallets = wallets;
        this.visibility = new CustomerVisibility(customers);
        this.invoices = invoices;
        this.issuer = issuer;
    }

    public Result execute(Command command) {
        try {
            return unitOfWork.transactional(() -> invoice(command));
        } catch (ConflictException billedMeanwhile) {
            // Another request, or the cycle job, billed the charge between our check and our insert.
            return unitOfWork.transactional(() -> invoices.findByCharge(command.chargeId().value()).map(existing -> new Result(existing, true))
                    .orElseThrow(() -> billedMeanwhile));
        }
    }

    private Result invoice(Command command) {
        ChargeSummary charge = charges.find(command.chargeId()).orElseThrow(() -> new NotFoundException("charge not found"));
        WalletSummary wallet = wallets.find(charge.walletId()).orElseThrow(() -> new NotFoundException("charge not found"));
        visibility.requireVisible(wallet.customerId(), command.caller(), () -> new NotFoundException("charge not found"));
        ChargeInvoiceIssuer.Issued issued = issuer.issue(charge, wallet, command.audit());
        return new Result(issued.invoice(), issued.replayed());
    }
}
