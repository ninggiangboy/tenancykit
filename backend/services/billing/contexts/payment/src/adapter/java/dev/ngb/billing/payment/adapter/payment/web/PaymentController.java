package dev.ngb.billing.payment.adapter.payment.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.payment.adapter.refund.web.RefundController.RefundResponse;
import dev.ngb.billing.payment.application.payment.command.CreateTopUpUseCase;
import dev.ngb.billing.payment.application.payment.query.PaymentAttemptView;
import dev.ngb.billing.payment.application.payment.query.PaymentListing;
import dev.ngb.billing.payment.application.payment.query.PaymentQueries;
import dev.ngb.billing.payment.application.payment.query.PaymentView;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import dev.ngb.billing.sharedkernel.id.WalletId;
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

/** Wallet top-ups and the payment history. Money-moving POSTs need an {@code Idempotency-Key}. Creating a payment never changes a balance; only a verified Stripe event does. */
@Tag(
        name = "Payments",
        description =
                "Wallet top-ups and the payment history. A payment never changes a balance; only a verified Stripe "
                + "event does.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    public record TopUpRequest(String walletId, long amount, String currency) {}

    public record PaymentResponse(
            String id, String walletId, String customerId, String status, MoneyDto amount, MoneyDto refunded, String checkoutUrl, String failureReason,
            String ledgerEntryId, Instant createdAt, Instant updatedAt, Instant completedAt) {
        static PaymentResponse of(Payment p) {
            // The hosted page is only useful while the customer can still pay.
            String url = p.status() == PaymentStatus.REQUIRES_ACTION ? p.checkoutUrl() : null;
            return new PaymentResponse(p.id().toString(), p.walletId().toString(), p.customerId().toString(), p.status().name(), MoneyDto.of(p.amount()),
                    MoneyDto.of(p.refunded()), url, p.failureReason(), p.ledgerEntryId() == null ? null : p.ledgerEntryId().toString(), p.createdAt(),
                    p.updatedAt(), p.completedAt());
        }

        static PaymentResponse ofView(PaymentView p) {
            String url = p.status() == PaymentStatus.REQUIRES_ACTION ? p.checkoutUrl() : null;
            return new PaymentResponse(p.id().toString(), p.walletId().toString(), p.customerId().toString(), p.status().name(), MoneyDto.of(p.amount()),
                    MoneyDto.of(p.refunded()), url, p.failureReason(), p.ledgerEntryId() == null ? null : p.ledgerEntryId().toString(), p.createdAt(),
                    p.updatedAt(), p.completedAt());
        }
    }

    public record AttemptResponse(String failureCode, String failureMessage, Instant occurredAt) {
        static AttemptResponse ofView(PaymentAttemptView a) {
            return new AttemptResponse(a.failureCode(), a.failureMessage(), a.occurredAt());
        }
    }

    public record PaymentDetailResponse(PaymentResponse payment, List<RefundResponse> refunds, List<AttemptResponse> failedAttempts) {}

    private final CreateTopUpUseCase topUp;
    private final PaymentQueries queries;
    private final CurrentActor currentActor;

    public PaymentController(CreateTopUpUseCase topUp, PaymentQueries queries, CurrentActor currentActor) {
        this.topUp = topUp;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /**
     * Starts a wallet top-up and returns the Stripe-hosted page where the customer pays. 201 for a new payment,
     * 200 when the {@code Idempotency-Key} was used before. The wallet is credited only after Stripe confirms.
     */
    @Operation(
            summary = "Start a wallet top-up",
            description =
                    "Starts a top-up of a wallet and answers the Stripe-hosted page where the customer pays. Creating a "
                    + "payment never changes a balance: the wallet is credited only when a verified Stripe event says the "
                    + "money arrived. Send an `Idempotency-Key`; a repeat of the same key and body answers the first result "
                    + "with `200`.")
    @PostMapping("/payments/top-ups")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.PAYMENT_CREATE)
    public ResponseEntity<PaymentResponse> createTopUp(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody TopUpRequest request) {
        if (request.currency() == null || request.walletId() == null) {
            throw new IllegalArgumentException("walletId and currency are required");
        }
        CreateTopUpUseCase.Result result = topUp.execute(new CreateTopUpUseCase.Command(WalletId.of(request.walletId()),
                Money.of(request.amount(), Currency.of(request.currency())), key, Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(PaymentResponse.of(result.payment()));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link PaymentListing}. */
    @Operation(
            summary = "List payments",
            description =
                    "Pages through the tenant's payments, with the list-query contract's filtering, sorting and paging. A "
                    + "Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/payments")
    @Listing(PaymentListing.class)
    @RequiresPermission(BillingPermissions.PAYMENT_READ)
    public Page<PaymentResponse> payments(ListQuery query) {
        return queries.list(query, currentActor.caller()).map(PaymentResponse::ofView);
    }

    @Operation(
            summary = "Get a payment",
            description =
                    "Returns one payment with its status and the amount refunded so far. An unknown id answers `404`.")
    @GetMapping("/payments/{id}")
    @RequiresPermission(BillingPermissions.PAYMENT_READ)
    public PaymentDetailResponse payment(@PathVariable String id) {
        PaymentQueries.PaymentDetail detail = queries.payment(UUID.fromString(id), currentActor.caller());
        return new PaymentDetailResponse(PaymentResponse.ofView(detail.payment()), detail.refunds().stream().map(RefundResponse::ofView).toList(),
                detail.attempts().stream().map(AttemptResponse::ofView).toList());
    }
}
