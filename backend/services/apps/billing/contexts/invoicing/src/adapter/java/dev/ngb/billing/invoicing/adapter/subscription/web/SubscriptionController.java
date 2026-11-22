package dev.ngb.billing.invoicing.adapter.subscription.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.invoicing.application.subscription.command.CancelSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.command.CreateSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionHistoryEntryView;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionListing;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionQueries;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionView;
import dev.ngb.billing.invoicing.domain.subscription.Subscription;
import dev.ngb.billing.invoicing.domain.subscription.SubscriptionId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.idempotency.RepeatableCreate;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Subscriptions. Money-moving POSTs that are not repeatable by data take an {@code Idempotency-Key}. A Tenant System sees and acts on the customers mapped to it only. */
@Tag(
        name = "Subscriptions",
        description =
                "Subscribe a wallet to a plan, read and cancel subscriptions.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class SubscriptionController {

    public record SubscriptionResponse(
            String id, String customerId, String walletId, String planId, String planCode, String cycle, String status, Instant anchor, int periodsCharged,
            Instant currentPeriodStart, Instant currentPeriodEnd, boolean cancelAtPeriodEnd, Instant canceledAt, Instant nextRenewalAt, int renewalAttempts,
            Instant createdAt) {
        static SubscriptionResponse of(SubscriptionView s) {
            return new SubscriptionResponse(s.id().toString(), s.customerId().toString(), s.walletId().toString(), s.planId().toString(), s.planCode(),
                    s.cycle().name(), s.status().name(), s.anchor(), s.periodsCharged(), s.currentPeriodStart(), s.currentPeriodEnd(), s.cancelAtPeriodEnd(),
                    s.canceledAt(), s.nextRenewalAt(), s.renewalAttempts(), s.createdAt());
        }
    }

    /** {@code amount} is the period price; {@code paidAmount} is what the wallet paid after coupons. */
    public record SubscriptionEventResponse(
            String id, String type, Integer periodNumber, Instant periodStart, Instant periodEnd, MoneyDto amount, MoneyDto paidAmount, String chargeId,
            String detail, Instant occurredAt) {
        static SubscriptionEventResponse of(SubscriptionHistoryEntryView e) {
            return new SubscriptionEventResponse(e.id().toString(), e.type().name(), e.periodNumber(), e.periodStart(), e.periodEnd(),
                    e.amount() == null ? null : MoneyDto.of(e.amount()), e.paidAmount() == null ? null : MoneyDto.of(e.paidAmount()),
                    e.chargeId() == null ? null : e.chargeId().toString(), e.detail(), e.occurredAt());
        }
    }

    public record SubscriptionDetailResponse(SubscriptionResponse subscription, List<SubscriptionEventResponse> history) {}

    public record SubscribeRequest(String walletId, String planId) {}

    private final SubscriptionQueries subscriptionQueries;
    private final CreateSubscriptionUseCase createSubscription;
    private final CancelSubscriptionUseCase cancelSubscription;
    private final CurrentActor currentActor;

    public SubscriptionController(
            SubscriptionQueries subscriptionQueries, CreateSubscriptionUseCase createSubscription, CancelSubscriptionUseCase cancelSubscription,
            CurrentActor currentActor) {
        this.subscriptionQueries = subscriptionQueries;
        this.createSubscription = createSubscription;
        this.cancelSubscription = cancelSubscription;
        this.currentActor = currentActor;
    }

    /**
     * Subscribes a customer's wallet to a plan and charges the first period at once. 201 for a new subscription, 200 when the
     * {@code Idempotency-Key} was used before. A first period the wallet cannot pay leaves it {@code PAST_DUE}, not rejected.
     */
    @Operation(
            summary = "Subscribe a wallet to a plan",
            description =
                    "Subscribes a customer's wallet (`walletId`) to a plan (`planId`) and charges the first period at "
                    + "once. The cycle and currency come from the plan and stay fixed for the life of the subscription. A "
                    + "first period the wallet cannot pay leaves the subscription `PAST_DUE` rather than rejecting it. Send "
                    + "an `Idempotency-Key`; a repeat of the same key and body answers the first result with `200`.")
    @PostMapping("/subscriptions")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.SUBSCRIPTION_MANAGE)
    public ResponseEntity<SubscriptionDetailResponse> subscribe(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody SubscribeRequest request) {
        if (request.walletId() == null || request.planId() == null) {
            throw new IllegalArgumentException("walletId and planId are required");
        }
        CreateSubscriptionUseCase.Result result = createSubscription.execute(new CreateSubscriptionUseCase.Command(WalletId.of(request.walletId()),
                UUID.fromString(request.planId()), key, Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(subscriptionDetail(result.subscription().id().value()));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link SubscriptionListing}. */
    @Operation(
            summary = "List subscriptions",
            description =
                    "Pages through the tenant's subscriptions, with the list-query contract's filtering, sorting and "
                    + "paging. A Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/subscriptions")
    @Listing(SubscriptionListing.class)
    @RequiresPermission(BillingPermissions.SUBSCRIPTION_READ)
    public Page<SubscriptionResponse> subscriptions(ListQuery query) {
        return subscriptionQueries.list(query, currentActor.caller()).map(SubscriptionResponse::of);
    }

    @Operation(
            summary = "Get a subscription",
            description =
                    "Returns one subscription with its history of periods. An unknown id answers `404`.")
    @GetMapping("/subscriptions/{id}")
    @RequiresPermission(BillingPermissions.SUBSCRIPTION_READ)
    public SubscriptionDetailResponse subscription(@PathVariable String id) {
        return subscriptionDetail(UUID.fromString(id));
    }

    /**
     * Cancels at the end of the paid period, or at once when no paid period is running. Repeating it changes nothing.
     * Billing stops no Resource: the Tenant System decides what the end means for it.
     */
    @Operation(
            summary = "Cancel a subscription",
            description =
                    "Cancels at the end of the period already paid, so the customer keeps what was paid for, or at once "
                    + "when no paid period is running. Repeating it changes nothing. Billing stops no resource: the Tenant "
                    + "System decides what the end means for it.")
    @PostMapping("/subscriptions/{id}/cancel")
    @RequiresPermission(BillingPermissions.SUBSCRIPTION_MANAGE)
    public SubscriptionDetailResponse cancel(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Subscription canceled = cancelSubscription.execute(new CancelSubscriptionUseCase.Command(SubscriptionId.of(id), Audit.of(currentActor.get().auditName(), requestId),
                currentActor.caller()));
        return subscriptionDetail(canceled.id().value());
    }

    private SubscriptionDetailResponse subscriptionDetail(UUID id) {
        SubscriptionQueries.SubscriptionDetail detail = subscriptionQueries.get(id, currentActor.caller());
        return new SubscriptionDetailResponse(SubscriptionResponse.of(detail.subscription()), detail.history().stream().map(SubscriptionEventResponse::of).toList());
    }
}
