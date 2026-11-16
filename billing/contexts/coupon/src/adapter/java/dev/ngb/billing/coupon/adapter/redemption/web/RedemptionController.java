package dev.ngb.billing.coupon.adapter.redemption.web;

import dev.ngb.billing.coupon.application.coupon.query.CouponQueries;
import dev.ngb.billing.coupon.application.redemption.command.RedeemCouponUseCase;
import dev.ngb.billing.coupon.application.redemption.command.RevokeRedemptionUseCase;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionView;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
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

/**
 * Redeems a coupon onto a wallet, revokes one, and reads a wallet's redemptions. Redeeming by code needs only
 * {@code billing:coupon:redeem}; attaching a coupon by its id bypasses a code's limits, so it needs
 * {@code billing:coupon:manage}, and the two are separate operations here rather than one endpoint that infers which
 * act the caller intended.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class RedemptionController {

    /** A redemption names a {@code code} or a {@code couponId}, never both. */
    public record RedeemRequest(String code, String couponId) {}

    public record RedemptionResponse(
            String id,
            String couponId,
            String couponName,
            String codeId,
            String walletId,
            String customerId,
            String status,
            Integer maxApplications,
            int applicationsUsed,
            Long maxTotalDiscount,
            long discountTotal,
            String currency,
            Instant redeemedAt,
            Instant endsAt,
            String endReason,
            Instant endedAt) {
        static RedemptionResponse of(RedemptionView v) {
            return new RedemptionResponse(v.id().toString(), v.couponId().toString(), v.couponName(), v.codeId() == null ? null : v.codeId().toString(),
                    v.walletId().toString(), v.customerId().toString(), v.status(), v.maxApplications(), v.applicationsUsed(),
                    v.maxTotalDiscount(), v.discountTotal(), v.currency(), v.redeemedAt(), v.endsAt(), v.endReason(), v.endedAt());
        }
    }

    private final RedeemCouponUseCase redeemCoupon;
    private final RevokeRedemptionUseCase revokeRedemption;
    private final CouponQueries queries;
    private final CurrentActor currentActor;

    public RedemptionController(
            RedeemCouponUseCase redeemCoupon, RevokeRedemptionUseCase revokeRedemption, CouponQueries queries, CurrentActor currentActor) {
        this.redeemCoupon = redeemCoupon;
        this.revokeRedemption = revokeRedemption;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /**
     * Redeems by code. 201 for a new redemption, 200 when the {@code Idempotency-Key} was used before. Every refusal
     * answers 404, so a caller cannot learn whether a code exists, is inactive, expired or exhausted.
     */
    @PostMapping("/wallets/{walletId}/coupon-redemptions")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.COUPON_REDEEM)
    public ResponseEntity<RedemptionResponse> redeemByCode(
            @PathVariable String walletId, @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody RedeemRequest request) {
        if (request.code() == null) {
            throw new IllegalArgumentException("a code is required to redeem");
        }
        RedeemCouponUseCase.Result result = redeemCoupon.execute(new RedeemCouponUseCase.Command(
                WalletId.of(walletId), request.code(), null, key, Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return redemptionResponse(result);
    }

    /**
     * Attaches a coupon to a wallet by its id, which asks for {@code billing:coupon:manage} because it skips a code's
     * own limits. A Tenant System uses this route to grant a coupon it chose itself.
     */
    @PostMapping("/wallets/{walletId}/coupon-redemptions/attach")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public ResponseEntity<RedemptionResponse> attach(
            @PathVariable String walletId, @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody RedeemRequest request) {
        if (request.couponId() == null) {
            throw new IllegalArgumentException("a couponId is required to attach");
        }
        RedeemCouponUseCase.Result result = redeemCoupon.execute(new RedeemCouponUseCase.Command(
                WalletId.of(walletId), null, request.couponId(), key, Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return redemptionResponse(result);
    }

    @GetMapping("/wallets/{walletId}/coupon-redemptions")
    @RequiresPermission(BillingPermissions.COUPON_READ)
    public List<RedemptionResponse> redemptionsOfWallet(
            @PathVariable String walletId, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.redemptionsOfWallet(UUID.fromString(walletId), currentActor.caller(), limit, offset).stream().map(RedemptionResponse::of).toList();
    }

    @GetMapping("/coupon-redemptions/{id}")
    @RequiresPermission(BillingPermissions.COUPON_READ)
    public RedemptionResponse redemption(@PathVariable String id) {
        return RedemptionResponse.of(queries.redemption(UUID.fromString(id), currentActor.caller()));
    }

    /** Ends the redemption early; nothing already discounted is released. Idempotent. */
    @PostMapping("/coupon-redemptions/{id}/revoke")
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public RedemptionResponse revoke(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Redemption redemption = revokeRedemption.execute(new RevokeRedemptionUseCase.Command(RedemptionId.of(id),
                Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return RedemptionResponse.of(queries.redemption(redemption.id().value(), currentActor.caller()));
    }

    private ResponseEntity<RedemptionResponse> redemptionResponse(RedeemCouponUseCase.Result result) {
        RedemptionResponse body = RedemptionResponse.of(queries.redemption(result.redemption().id().value(), currentActor.caller()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
