package dev.ngb.billing.invoicing.adapter.invoice.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.invoicing.application.invoice.command.InvoiceChargeUseCase;
import dev.ngb.billing.invoicing.application.invoice.command.ManageInvoiceUseCase;
import dev.ngb.billing.invoicing.application.invoice.command.PayInvoiceUseCase;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceItemView;
import dev.ngb.billing.invoicing.application.invoice.query.InvoicePaymentView;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceListing;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceQueries;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceView;
import dev.ngb.billing.invoicing.domain.invoice.Invoice;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceId;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invoices. An invoice becomes paid only from a verified payment (or because the wallet already paid the charge it documents); no request here marks one paid. A Tenant System sees and acts on the customers mapped to it only. */
@Tag(
        name = "Invoices",
        description =
                "Invoices. An invoice becomes paid only from a verified payment; no request marks one paid.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class InvoiceController {

    public record ItemResponse(
            String id, String source, String chargeId, String subscriptionId, Instant periodStart, Instant periodEnd, String description, MoneyDto amount,
            Instant incurredAt) {
        static ItemResponse of(InvoiceItemView i) {
            return new ItemResponse(i.id().toString(), i.source().name(), i.chargeId().toString(), i.subscriptionId() == null ? null : i.subscriptionId().toString(),
                    i.periodStart(), i.periodEnd(), i.description(), MoneyDto.of(i.amount()), i.incurredAt());
        }
    }

    public record InvoicePaymentResponse(String paymentId, MoneyDto amount, String status, String failureReason, String ledgerEntryId, Instant startedAt, Instant settledAt) {
        static InvoicePaymentResponse of(InvoicePaymentView p) {
            return new InvoicePaymentResponse(p.paymentId().toString(), MoneyDto.of(p.amount()), p.status().name(), p.failureReason(),
                    p.ledgerEntryId() == null ? null : p.ledgerEntryId().toString(), p.startedAt(), p.settledAt());
        }
    }

    public record InvoiceResponse(
            String id, String number, String customerId, String walletId, String status, String origin, MoneyDto total, Instant periodStart, Instant periodEnd,
            String settlement, String voidReason, Instant createdAt, Instant issuedAt, Instant paidAt, Instant voidedAt) {
        static InvoiceResponse of(InvoiceView i) {
            return new InvoiceResponse(i.id().toString(), i.number(), i.customerId().toString(), i.walletId().toString(), i.status().name(), i.origin().name(),
                    MoneyDto.of(i.total()), i.periodStart(), i.periodEnd(), i.settlement() == null ? null : i.settlement().name(), i.voidReason(), i.createdAt(),
                    i.issuedAt(), i.paidAt(), i.voidedAt());
        }
    }

    public record InvoiceDetailResponse(InvoiceResponse invoice, List<ItemResponse> items, List<InvoicePaymentResponse> payments) {
        static InvoiceDetailResponse of(InvoiceQueries.InvoiceDetail d) {
            return new InvoiceDetailResponse(InvoiceResponse.of(d.invoice()), d.items().stream().map(ItemResponse::of).toList(),
                    d.payments().stream().map(InvoicePaymentResponse::of).toList());
        }
    }

    /** {@code checkoutUrl} is Stripe's hosted page; it is present while the customer can still pay. */
    public record StartedPaymentResponse(String id, String status, String checkoutUrl) {}

    public record PayInvoiceResponse(InvoiceDetailResponse invoice, StartedPaymentResponse payment) {}

    public record InvoiceChargeRequest(String chargeId) {}

    public record VoidRequest(String reason) {}

    private final InvoiceQueries invoiceQueries;
    private final InvoiceChargeUseCase invoiceCharge;
    private final PayInvoiceUseCase payInvoice;
    private final ManageInvoiceUseCase manageInvoice;
    private final CurrentActor currentActor;

    public InvoiceController(
            InvoiceQueries invoiceQueries, InvoiceChargeUseCase invoiceCharge, PayInvoiceUseCase payInvoice, ManageInvoiceUseCase manageInvoice,
            CurrentActor currentActor) {
        this.invoiceQueries = invoiceQueries;
        this.invoiceCharge = invoiceCharge;
        this.payInvoice = payInvoice;
        this.manageInvoice = manageInvoice;
        this.currentActor = currentActor;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link InvoiceListing}. */
    @Operation(
            summary = "List invoices",
            description =
                    "Pages through the tenant's invoices, with the list-query contract's filtering, sorting and paging. A "
                    + "Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/invoices")
    @Listing(InvoiceListing.class)
    @RequiresPermission(BillingPermissions.INVOICE_READ)
    public Page<InvoiceResponse> invoices(ListQuery query) {
        return invoiceQueries.list(query, currentActor.caller()).map(InvoiceResponse::of);
    }

    @Operation(
            summary = "Get an invoice",
            description =
                    "Returns one invoice with its lines. An invoice becomes paid only from a verified payment, or because "
                    + "the wallet already paid the charge it documents; no request marks one paid. An unknown id answers "
                    + "`404`.")
    @GetMapping("/invoices/{id}")
    @RequiresPermission(BillingPermissions.INVOICE_READ)
    public InvoiceDetailResponse invoice(@PathVariable String id) {
        return InvoiceDetailResponse.of(invoiceQueries.get(UUID.fromString(id), currentActor.caller()));
    }

    /**
     * Issues an invoice for one succeeded charge. 201 for a new invoice, 200 when the charge already had one, which is
     * returned: a charge is billed on one line at most, so no key is needed.
     */
    @Operation(
            summary = "Invoice a charge",
            description =
                    "Issues an invoice for one succeeded charge, with the same numbering as a cycle invoice. A charge is "
                    + "billed on one line at most, so asking again answers the existing invoice with `200` and needs no "
                    + "key. A postpaid charge opens for payment; a prepaid charge was paid from the wallet, so its invoice "
                    + "is a paid record. Answers `201`.")
    @PostMapping("/invoices")
    @RepeatableCreate(repeated = "The charge already had an invoice; that invoice is returned")
    @RequiresPermission(BillingPermissions.INVOICE_CREATE)
    public ResponseEntity<InvoiceDetailResponse> invoiceCharge(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody InvoiceChargeRequest request) {
        if (request.chargeId() == null) {
            throw new IllegalArgumentException("chargeId is required");
        }
        InvoiceChargeUseCase.Result result = invoiceCharge.execute(new InvoiceChargeUseCase.Command(ChargeId.of(request.chargeId()),
                Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        InvoiceQueries.InvoiceDetail detail = invoiceQueries.get(result.invoice().id().value(), currentActor.caller());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(InvoiceDetailResponse.of(detail));
    }

    /**
     * Starts a payment of an open invoice and returns Stripe's hosted page. The invoice is paid, and the debt paid off,
     * only when Stripe's verified result arrives. 201 for a new payment, 200 when the {@code Idempotency-Key} was used before.
     */
    @Operation(
            summary = "Pay an invoice",
            description =
                    "Starts a payment of an open invoice and answers Stripe's hosted page where the customer pays. The "
                    + "invoice is paid, and the debt paid off, only when Stripe's verified result arrives. Send an "
                    + "`Idempotency-Key`; a repeat of the same key and body answers the first result with `200`.")
    @PostMapping("/invoices/{id}/payments")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.PAYMENT_CREATE)
    public ResponseEntity<PayInvoiceResponse> pay(
            @PathVariable String id, @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        PayInvoiceUseCase.Result result = payInvoice.execute(new PayInvoiceUseCase.Command(InvoiceId.of(id), key, Audit.of(currentActor.get().auditName(), requestId),
                currentActor.caller()));
        InvoiceQueries.InvoiceDetail detail = invoiceQueries.get(result.invoice().id().value(), currentActor.caller());
        PayInvoiceResponse body = new PayInvoiceResponse(InvoiceDetailResponse.of(detail), new StartedPaymentResponse(result.payment().paymentId().toString(),
                result.payment().status(), result.payment().checkoutUrl()));
        return ResponseEntity.status(result.payment().replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** Cancels the request for payment. The wallet is not touched and a paid invoice cannot be voided (409). */
    @Callers(Caller.TENANT_USER)
    @Operation(
            summary = "Void an invoice",
            description =
                    "Cancels the request for payment, with a `reason`. The wallet is not touched: what was charged stays "
                    + "charged, and correcting the ledger is a reversal, not a void. A paid invoice cannot be voided and "
                    + "answers `409`.")
    @PostMapping("/invoices/{id}/void")
    @RequiresPermission(BillingPermissions.INVOICE_MANAGE)
    public InvoiceDetailResponse voidInvoice(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody VoidRequest request) {
        Invoice voided = manageInvoice.voidInvoice(InvoiceId.of(id), request.reason(), Audit.of(currentActor.get().auditName(), requestId));
        return InvoiceDetailResponse.of(invoiceQueries.get(voided.id().value(), currentActor.caller()));
    }

    /** Writes an open invoice off as unlikely to be paid. It can still be paid later. */
    @Callers(Caller.TENANT_USER)
    @Operation(
            summary = "Write an invoice off",
            description =
                    "Writes an open invoice off as unlikely to be paid. It can still be paid later.")
    @PostMapping("/invoices/{id}/uncollectible")
    @RequiresPermission(BillingPermissions.INVOICE_MANAGE)
    public InvoiceDetailResponse markUncollectible(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Invoice written = manageInvoice.markUncollectible(InvoiceId.of(id), Audit.of(currentActor.get().auditName(), requestId));
        return InvoiceDetailResponse.of(invoiceQueries.get(written.id().value(), currentActor.caller()));
    }
}
