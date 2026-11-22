package dev.ngb.billing.payment.adapter.refund.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.payment.application.refund.command.RequestRefundUseCase;
import dev.ngb.billing.payment.application.refund.query.RefundListing;
import dev.ngb.billing.payment.application.refund.query.RefundQueries;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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

/** Refunds of payments. Money-moving POSTs need an {@code Idempotency-Key}. */
@Tag(
        name = "Refunds",
        description =
                "Refunds of payments.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class RefundController {

    public record RefundRequest(Long amount, String currency, String reason) {}

    public record RefundResponse(
            String id, String paymentId, String status, MoneyDto amount, String reason, String failureReason, String ledgerEntryId, Instant createdAt,
            Instant completedAt) {
        static RefundResponse of(Refund r) {
            return new RefundResponse(r.id().toString(), r.paymentId().toString(), r.status().name(), MoneyDto.of(r.amount()), r.reason(),
                    r.failureReason(), r.ledgerEntryId() == null ? null : r.ledgerEntryId().toString(), r.createdAt(), r.completedAt());
        }

        public static RefundResponse ofView(RefundView r) {
            return new RefundResponse(r.id().toString(), r.paymentId().toString(), r.status().name(), MoneyDto.of(r.amount()), r.reason(),
                    r.failureReason(), r.ledgerEntryId() == null ? null : r.ledgerEntryId().toString(), r.createdAt(), r.completedAt());
        }
    }

    private final RequestRefundUseCase refund;
    private final RefundQueries refundQueries;
    private final CurrentActor currentActor;

    public RefundController(RequestRefundUseCase refund, RefundQueries refundQueries, CurrentActor currentActor) {
        this.refund = refund;
        this.refundQueries = refundQueries;
        this.currentActor = currentActor;
    }

    /**
     * Refunds a payment, in full when {@code amount} is omitted. The unspent balance is held at once, Stripe is
     * asked, and the wallet is debited when Stripe confirms. 409 when the amount exceeds what is left to refund
     * or the wallet has already spent it.
     */
    @Operation(
            summary = "Refund a payment",
            description =
                    "Sends money back to the customer's card, in full when `amount` is omitted. The unspent balance is "
                    + "held at once, Stripe is asked, and the wallet is debited only when Stripe confirms. Answers `409` "
                    + "when the amount exceeds what is left to refund or the wallet has already spent it. Send an "
                    + "`Idempotency-Key`; a repeat of the same key and body answers the first result with `200`.")
    @PostMapping("/payments/{id}/refunds")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.REFUND_CREATE)
    public ResponseEntity<RefundResponse> requestRefund(
            @PathVariable String id, @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody RefundRequest request) {
        Money amount = null;
        if (request.amount() != null) {
            if (request.currency() == null) {
                throw new IllegalArgumentException("currency is required with an amount");
            }
            amount = Money.of(request.amount(), Currency.of(request.currency()));
        }
        RequestRefundUseCase.Result result = refund.execute(new RequestRefundUseCase.Command(
                PaymentId.of(id), amount, request.reason(), key, currentActor.caller(),
                Audit.of(currentActor.get().auditName(), requestId)));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(RefundResponse.of(result.refund()));
    }

    /** Filter, sort and paging follow the list-query contract; a Tenant System sees only its customers' refunds. */
    @Operation(
            summary = "List refunds",
            description =
                    "Pages through the tenant's refunds, with the list-query contract's filtering, sorting and paging. A "
                    + "Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/refunds")
    @Listing(RefundListing.class)
    @RequiresPermission(BillingPermissions.PAYMENT_READ)
    public Page<RefundResponse> refunds(ListQuery query) {
        return refundQueries.list(query, currentActor.caller()).map(RefundResponse::ofView);
    }

    @Operation(
            summary = "Get a refund",
            description =
                    "Returns one refund with its status and failure reason. An unknown id answers `404`.")
    @GetMapping("/refunds/{id}")
    @RequiresPermission(BillingPermissions.PAYMENT_READ)
    public RefundResponse getRefund(@PathVariable String id) {
        return RefundResponse.ofView(refundQueries.get(UUID.fromString(id), currentActor.caller()));
    }
}
