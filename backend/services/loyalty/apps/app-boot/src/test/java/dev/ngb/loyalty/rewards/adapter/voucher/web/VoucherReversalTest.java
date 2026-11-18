package dev.ngb.loyalty.rewards.adapter.voucher.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.RewardsApiTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a reversal of a reward redemption does to the voucher it issued: an unused one is voided with {@code REVERSAL} and
 * gives its unit of stock back, a used one refuses the reversal and changes nothing.
 *
 * <p>The hook runs inside the reversal's own transaction, after the member's row is locked and before the points go back.
 * So a refusal leaves the redemption active and its ledger untouched, which is the whole reason it is a hook rather than a
 * second call.
 */
class VoucherReversalTest extends RewardsApiTest {

    @Test
    void reversingAnUnusedVoucherReturnsThePointsTheStockAndVoidsIt() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        String code = redeemed.json().get("voucher").get("code").asString();
        UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());
        assertThat(available(tenant, "M-1")).isEqualTo(60);
        assertThat(stock(tenant, rewardId)).isEqualTo(4);

        ApiResponse reversed = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(reversed.status()).as("the reversal answered " + reversed.body()).isEqualTo(200);
        assertThat(reversed.json().get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.json().get("available").asLong()).as("the points came back").isEqualTo(100);
        assertThat(available(tenant, "M-1")).isEqualTo(100);
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isEqualTo(100);
        assertThat(stock(tenant, rewardId)).as("the unit of stock came back with it").isEqualTo(5);

        ApiResponse voucher = getVoucher(tenant, code);
        assertThat(voucher.json().get("state").asString()).isEqualTo("VOIDED");
        assertThat(voucher.json().get("voidReason").asString()).isEqualTo("REVERSAL");
        assertThat(voucher.json().get("usedAt").isNull()).as("a voided voucher was never used").isTrue();
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'ISSUED'")).isZero();
        assertThat(outboxRows(tenant, "voucher.voided")).isEqualTo(1);

        // The voided voucher cannot be used afterwards, and the reversal replays without moving anything.
        assertThat(useVoucher(tenant, code, "USE-1").code()).isEqualTo("VOUCHER_USED");
        assertThat(reverseRedemption(tenant, "M-1", redemptionId).status()).isEqualTo(200);
        assertThat(stock(tenant, rewardId)).as("a replayed reversal returns no second unit").isEqualTo(5);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM_REVERSAL'")).isEqualTo(1);
        assertThat(outboxRows(tenant, "voucher.voided")).isEqualTo(1);
    }

    @Test
    void reversingARedemptionWhoseVoucherWasUsedIsRefusedAndChangesNothing() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        String code = redeemed.json().get("voucher").get("code").asString();
        UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());
        assertThat(useVoucher(tenant, code, "USE-1").status()).isEqualTo(200);

        ApiResponse refused = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("VOUCHER_ALREADY_USED");

        // Nothing moved: the redemption is still active, the points are still spent, the stock is still taken, and the
        // voucher is still the one the POS used.
        assertThat(scalar(tenant, "select count(*) from redemptions where status = 'ACTIVE'")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from redemptions where status = 'REVERSED'")).isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM_REVERSAL'")).isZero();
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isEqualTo(60);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
        assertThat(stock(tenant, rewardId)).as("the unit stays taken").isEqualTo(4);
        assertThat(getVoucher(tenant, code).json().get("state").asString()).isEqualTo("USED");
        assertThat(getVoucher(tenant, code).json().get("voidReason").isNull()).isTrue();
        assertThat(outboxRows(tenant, "voucher.voided")).isZero();
    }

    /**
     * The hook runs inside the reversal's transaction: a failure after the reversal has voided the voucher and returned
     * the stock unwinds all three, leaving the redemption exactly as it was. The assertions inside the transaction are
     * what proves the ordering — by the time the reversal has answered, the voucher is already voided.
     */
    @Test
    void aReversalThatFailsAfterTheHookRollsBackTheVoidAndTheStock() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        String code = redeemed.json().get("voucher").get("code").asString();
        UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());
        assertThat(stock(tenant, rewardId)).isEqualTo(4);

        assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(() -> {
            assertThat(reverseRedemption(tenant, "M-1", redemptionId).status()).isEqualTo(200);
            assertThat(getVoucher(tenant, code).json().get("state").asString())
                    .as("inside the reversal's own transaction the voucher is voided and the unit returned")
                    .isEqualTo("VOIDED");
            assertThat(stock(tenant, rewardId)).isEqualTo(5);
            throw new IllegalStateException("the reversal could not be committed");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(getVoucher(tenant, code).json().get("state").asString())
                .as("the void went back with the transaction")
                .isEqualTo("ISSUED");
        assertThat(getVoucher(tenant, code).json().get("voidReason").isNull()).isTrue();
        assertThat(stock(tenant, rewardId)).as("the returned unit went back with it").isEqualTo(4);
        assertThat(scalar(tenant, "select count(*) from redemptions where status = 'ACTIVE'")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM_REVERSAL'")).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(60);
        assertThat(outboxRows(tenant, "voucher.voided")).isZero();

        // The same reversal outside the rollback does all of it, so the silence above is the rollback's.
        assertThat(reverseRedemption(tenant, "M-1", redemptionId).status()).isEqualTo(200);
        assertThat(getVoucher(tenant, code).json().get("state").asString()).isEqualTo("VOIDED");
        assertThat(stock(tenant, rewardId)).isEqualTo(5);
        assertThat(available(tenant, "M-1")).isEqualTo(100);
    }

    /**
     * A plain spend issued no voucher, so the hook has nothing to void and the reversal proceeds: it is the same
     * transaction, and a redemption without a voucher is not a refusal.
     */
    @Test
    void reversingAPlainSpendVoidsNothingAndStillRestoresThePoints() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 30, "RECEIPT-1").json().get("redemptionId").asString());

        ApiResponse reversed = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(reversed.status()).isEqualTo(200);
        assertThat(reversed.json().get("available").asLong()).isEqualTo(100);
        assertThat(vouchers(tenant)).isZero();
        assertThat(outboxRows(tenant, "voucher.voided")).isZero();
    }

    @Test
    void anUnknownCodesReversalIsNotReachableThroughTheVoucherSurface() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);

        // There is no voucher to reverse, so the reversal of an unknown redemption is the points context's 404.
        ApiResponse refused = reverseRedemption(tenant, "M-1", UUID.randomUUID());

        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.code()).isEqualTo("REDEMPTION_NOT_FOUND");
        assertThat(vouchers(tenant)).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(100);
    }
}
