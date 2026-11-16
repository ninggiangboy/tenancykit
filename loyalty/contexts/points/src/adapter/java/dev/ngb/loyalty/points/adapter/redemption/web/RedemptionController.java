package dev.ngb.loyalty.points.adapter.redemption.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.loyalty.points.application.redemption.command.RedeemPointsUseCase;
import dev.ngb.loyalty.points.application.redemption.command.ReverseRedemptionUseCase;
import dev.ngb.loyalty.points.application.redemption.query.RedemptionQueries;
import dev.ngb.loyalty.points.application.redemption.query.RedemptionView;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
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

/**
 * Spending a member's points, reading the spend and undoing it.
 *
 * <p>A spend is idempotent by the caller's `Idempotency-Key`: the same key and body answer the stored redemption with
 * 200, and a different body is a conflict rather than a second spend. The reversal needs no key of its own, because the
 * redemption it names is already one spend: reversing it twice answers the same reversed redemption.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class RedemptionController {

    /** The spend a caller asks for: the points and its own reference, such as a POS receipt. */
    public record RedemptionRequest(long points, String reference) {}

    /** One redemption, with the balance it left the member holding. */
    public record RedemptionResponse(
            UUID redemptionId,
            String externalRef,
            long points,
            String reference,
            String status,
            long available,
            long debt,
            Instant createdAt,
            Instant reversedAt) {

        static RedemptionResponse of(RedeemPointsUseCase.Result result) {
            return new RedemptionResponse(
                    result.redemptionId(),
                    result.externalRef(),
                    result.points(),
                    result.reference(),
                    result.status(),
                    result.available(),
                    result.debt(),
                    result.createdAt(),
                    result.reversedAt());
        }

        static RedemptionResponse of(ReverseRedemptionUseCase.Result result) {
            return new RedemptionResponse(
                    result.redemptionId(),
                    result.externalRef(),
                    result.points(),
                    result.reference(),
                    result.status(),
                    result.available(),
                    result.debt(),
                    result.createdAt(),
                    result.reversedAt());
        }

        static RedemptionResponse of(RedemptionView view) {
            return new RedemptionResponse(
                    view.redemptionId(),
                    view.externalRef(),
                    view.points(),
                    view.reference(),
                    view.status(),
                    view.available(),
                    view.debt(),
                    view.createdAt(),
                    view.reversedAt());
        }
    }

    private final RedeemPointsUseCase redeem;
    private final ReverseRedemptionUseCase reverse;
    private final RedemptionQueries redemptions;

    public RedemptionController(
            RedeemPointsUseCase redeem, ReverseRedemptionUseCase reverse, RedemptionQueries redemptions) {
        this.redeem = redeem;
        this.reverse = reverse;
        this.redemptions = redemptions;
    }

    /** 201 when this call spent the points, 200 when the key had already spent them with the same body. */
    @PostMapping("/members/{externalRef}/redemptions")
    @RequiresPermission(LoyaltyPermissions.POINTS_REDEEM)
    public ResponseEntity<RedemptionResponse> postRedemption(
            @PathVariable String externalRef,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) RedemptionRequest request) {
        RedeemPointsUseCase.Result result = redeem.execute(new RedeemPointsUseCase.Command(
                externalRef,
                idempotencyKey,
                request == null ? 0L : request.points(),
                request == null ? null : request.reference()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RedemptionResponse.of(result));
    }

    @GetMapping("/members/{externalRef}/redemptions/{id}")
    @RequiresPermission(LoyaltyPermissions.MEMBER_READ)
    public RedemptionResponse getRedemption(@PathVariable String externalRef, @PathVariable UUID id) {
        return RedemptionResponse.of(redemptions.get(externalRef, id));
    }

    /** Restores the lots the redemption consumed; an already reversed redemption replays with 200. */
    @PostMapping("/members/{externalRef}/redemptions/{id}/reversal")
    @RequiresPermission(LoyaltyPermissions.POINTS_REDEEM)
    public ResponseEntity<RedemptionResponse> postReversal(@PathVariable String externalRef, @PathVariable UUID id) {
        ReverseRedemptionUseCase.Result result =
                reverse.execute(new ReverseRedemptionUseCase.Command(externalRef, id));
        return ResponseEntity.ok(RedemptionResponse.of(result));
    }
}
