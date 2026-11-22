package dev.ngb.billing.boot.portal;

import dev.ngb.billing.payment.application.payment.query.PaymentAttemptView;
import dev.ngb.billing.payment.application.payment.query.PaymentQueries;
import dev.ngb.billing.payment.application.payment.query.PaymentView;
import dev.ngb.billing.payment.application.refund.query.RefundListing;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.session.api.portal.PortalPermission;
import dev.ngb.billing.session.api.portal.RequiresPortalPermission;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import dev.ngb.billing.payment.application.payment.query.PaymentListing;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The session's customer's payments, refunds and the Stripe-hosted page for managing payment methods. */
@Tag(
        name = "Portal: payments",
        description =
                "The session's customer's payments, refunds and the Stripe-hosted payment-methods page.")
@Profile("api")
@ConditionalOnProperty(name = "billing.portal.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/portal/api")
public class PortalPaymentController {

    public record PaymentResponse(
            String id, String walletId, String status, MoneyDto amount, MoneyDto refunded, String checkoutUrl, String failureReason,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        static PaymentResponse of(PaymentView payment) {
            String url = payment.status() == PaymentStatus.REQUIRES_ACTION ? payment.checkoutUrl() : null;
            return new PaymentResponse(payment.id().toString(), payment.walletId().toString(), payment.status().name(), MoneyDto.of(payment.amount()),
                    MoneyDto.of(payment.refunded()), url, payment.failureReason(), payment.createdAt(), payment.updatedAt(), payment.completedAt());
        }
    }

    public record AttemptResponse(String failureCode, String failureMessage, Instant occurredAt) {
        static AttemptResponse of(PaymentAttemptView attempt) {
            return new AttemptResponse(attempt.failureCode(), attempt.failureMessage(), attempt.occurredAt());
        }
    }

    public record PaymentDetailResponse(PaymentResponse payment, List<RefundResponse> refunds, List<AttemptResponse> failedAttempts) {}

    public record RefundResponse(
            String id, String paymentId, String status, MoneyDto amount, String reason, String failureReason, Instant createdAt, Instant completedAt) {
        static RefundResponse of(RefundView refund) {
            return new RefundResponse(refund.id().toString(), refund.paymentId().toString(), refund.status().name(), MoneyDto.of(refund.amount()),
                    refund.reason(), refund.failureReason(), refund.createdAt(), refund.completedAt());
        }
    }

    public record HostedPageResponse(String url) {}

    private final PortalReadModel portal;

    public PortalPaymentController(PortalReadModel portal) {
        this.portal = portal;
    }

    /** The customer is fixed by the portal session; the same filter, sort and paging contract as the API applies. */
    @Operation(
            summary = "List the customer's payments",
            description =
                    "Pages through the payments of the portal session's customer, with the list-query contract's "
                    + "filtering, sorting and paging. The customer is fixed by the portal session, so no other customer's "
                    + "data is reachable.")
    @GetMapping("/payments")
    @Listing(PaymentListing.class)
    @RequiresPortalPermission(PortalPermission.PAYMENT_VIEW)
    public Page<PaymentResponse> payments(ListQuery query) {
        return portal.payments(query).map(PaymentResponse::of);
    }

    @Operation(
            summary = "Get one of the customer's payments",
            description =
                    "Returns a payment of the portal session's customer with its status. A payment of another customer "
                    + "answers `404`.")
    @GetMapping("/payments/{id}")
    @RequiresPortalPermission(PortalPermission.PAYMENT_VIEW)
    public PaymentDetailResponse payment(@PathVariable String id) {
        PaymentQueries.PaymentDetail detail = portal.payment(UUID.fromString(id));
        return new PaymentDetailResponse(PaymentResponse.of(detail.payment()), detail.refunds().stream().map(RefundResponse::of).toList(),
                detail.attempts().stream().map(AttemptResponse::of).toList());
    }

    /** Refunds of the session's customer's payments; the same filter, sort and paging contract as the API applies. */
    @Operation(
            summary = "List the customer's refunds",
            description =
                    "Pages through the refunds of the portal session's customer's payments, with the list-query "
                    + "contract's filtering, sorting and paging. The customer is fixed by the portal session, so no other "
                    + "customer's data is reachable.")
    @GetMapping("/refunds")
    @Listing(RefundListing.class)
    @RequiresPortalPermission(PortalPermission.REFUND_VIEW)
    public Page<RefundResponse> refunds(ListQuery query) {
        return portal.refunds(query).map(RefundResponse::of);
    }

    /**
     * Opens Stripe's hosted page for managing payment methods. The provider customer is created once per Billing
     * Customer and Stripe configuration, and the page is the Tenant's own Stripe account.
     */
    @Operation(
            summary = "Open the payment-methods page",
            description =
                    "Opens Stripe's hosted page where the customer manages payment methods, in the Tenant's own Stripe "
                    + "account. The Stripe customer is created once per billing customer and Stripe configuration and "
                    + "reused afterwards.")
    @PostMapping("/payment-methods")
    @RequiresPortalPermission(PortalPermission.PAYMENT_METHOD_MANAGE)
    public HostedPageResponse paymentMethods(HttpServletRequest request) {
        return new HostedPageResponse(portal.paymentMethodsLink(PortalUrls.origin(request) + "/portal/payment-methods/done"));
    }
}
