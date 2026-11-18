package dev.ngb.loyalty.boot.rewards;

import dev.ngb.loyalty.points.application.redemption.port.RedemptionReversalHook;
import dev.ngb.loyalty.rewards.application.voucher.service.RedemptionVoidService;
import java.util.UUID;

/**
 * Undoes a reward redemption's voucher with the reversal that restores its points.
 *
 * <p>It is the seam the context graph needs: the points context declares what a reversal must undo first, and this
 * adapter answers with the feature that issued the voucher, so neither context imports the other's internals. It holds no
 * state and decides nothing itself: the void rule lives in the rewards context, and a refusal propagates out of the
 * reversal's transaction so the redemption stays as it was.
 */
public class RedemptionVoucherHook implements RedemptionReversalHook {

    private final RedemptionVoidService voidService;

    public RedemptionVoucherHook(RedemptionVoidService voidService) {
        this.voidService = voidService;
    }

    @Override
    public void beforeReversal(UUID memberId, UUID redemptionId) {
        voidService.voidForReversal(memberId, redemptionId);
    }
}
