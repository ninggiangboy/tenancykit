package dev.ngb.loyalty.rewards.adapter.reward.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.RewardsApiTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Redeeming a reward: one transaction that spends the member's points through the one spend function, takes one unit of
 * stock and issues one voucher, or does none of the three.
 *
 * <p>The cases below state what the transaction promises at its edges — a replay by key issues nothing new, a key reused
 * for another request is a conflict, a refusal after the spend gives the points back — because those are the failures a
 * member would see twice if they were wrong.
 */
class RewardRedemptionIntegrationTest extends RewardsApiTest {

    @Test
    void aRedemptionSpendsThePointsAndIssuesOneVoucher() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);

        ApiResponse redeemed = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(redeemed.status()).as("the redemption answered " + redeemed.body()).isEqualTo(201);
        assertThat(redeemed.json().get("rewardId").asString()).isEqualTo(rewardId);
        assertThat(redeemed.json().get("redemptionId").asString()).isNotBlank();
        assertThat(redeemed.json().get("available").asLong()).as("the cost came out of the balance").isEqualTo(60);
        assertThat(redeemed.json().get("debt").asLong()).isZero();
        assertThat(redeemed.json().get("createdAt").asString()).isNotBlank();

        var voucher = redeemed.json().get("voucher");
        assertThat(voucher.get("code").asString()).as("a twelve-character code").hasSize(12);
        assertThat(voucher.get("rewardId").asString()).isEqualTo(rewardId);
        assertThat(voucher.get("state").asString()).isEqualTo("ISSUED");
        assertThat(voucher.get("issuedAt").asString()).isNotBlank();
        assertThat(voucher.get("expiresAt").asString()).as("valid for the reward's own lifetime").isNotBlank();
        assertThat(java.time.Instant.parse(voucher.get("expiresAt").asString()))
                .isAfter(java.time.Instant.parse(voucher.get("issuedAt").asString()).plusSeconds(6 * 86_400));

        // One spend, one voucher, one unit of stock: all three, or none of them.
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(redeemedTotal(tenant)).as("the points were spent exactly once").isEqualTo(40);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM_REVERSAL'")).isZero();
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, rewardId)).as("one unit of stock was taken").isEqualTo(4);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
    }

    @Test
    void theSameIdempotencyKeyAnswersTheSameVoucherAndIssuesNothingNew() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);

        ApiResponse first = redeemReward(tenant, "M-1", "K-1", rewardId);
        ApiResponse again = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).as("the key had issued a voucher, so it replays").isEqualTo(200);
        assertThat(again.json().get("voucher").get("voucherId").asString())
                .as("the same voucher, not a second one")
                .isEqualTo(first.json().get("voucher").get("voucherId").asString());
        assertThat(again.json().get("voucher").get("code").asString())
                .isEqualTo(first.json().get("voucher").get("code").asString());
        assertThat(again.json().get("redemptionId").asString())
                .isEqualTo(first.json().get("redemptionId").asString());
        assertThat(again.json().get("available").asLong()).isEqualTo(60);

        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(redeemedTotal(tenant)).isEqualTo(40);
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, rewardId)).as("a replay takes no stock").isEqualTo(4);
    }

    /**
     * The body of a reward redemption is the reward it names, so the same key naming another reward is another request.
     * The contract answers it with {@code IDEMPOTENCY_CONFLICT}, exactly as the plain spend does.
     */
    @Test
    void theSameKeyNamingAnotherRewardIsAConflict() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String coffee = seedReward(tenant, "Coffee", 40L, 5L, 7);
        String tea = seedReward(tenant, "Tea", 40L, 5L, 7);

        assertThat(redeemReward(tenant, "M-1", "K-1", coffee).status()).isEqualTo(201);
        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", tea);

        assertThat(refused.status()).as("a key reused for another reward answered " + refused.body()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, tea)).as("the refused redemption took no stock").isEqualTo(5);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
    }

    /**
     * Two catalog entries may carry the same name and the same cost, and the spend the key wrote cannot tell them apart:
     * its fingerprint holds the member, the points and the reference, and the reference is the reward's name. So a key
     * reused for the other entry is another request, and answering it with the stored voucher would hand the caller a
     * voucher for a reward it did not name — the answer would name the requested {@code rewardId} while the voucher
     * inside it named the other one.
     */
    @Test
    void theSameKeyNamingAnotherRewardOfTheSameNameAndCostIsAConflict() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String coffee = seedReward(tenant, "Coffee", 40L, 5L, 7);
        String otherCoffee = seedReward(tenant, "Coffee", 40L, 5L, 7);

        ApiResponse first = redeemReward(tenant, "M-1", "K-1", coffee);
        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", otherCoffee);

        assertThat(first.status()).isEqualTo(201);
        assertThat(refused.status()).as("a key reused for another reward answered " + refused.body()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(first.json().get("voucher").get("rewardId").asString())
                .as("the first voucher names the reward it was issued for")
                .isEqualTo(coffee);
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, otherCoffee)).as("the refused redemption took no unit of the other reward").isEqualTo(5);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
    }

    @Test
    void aKeyReusedForADifferentCostIsAConflict() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        assertThat(patchReward(tenant, rewardId, "\"1\"", "{\"pointsCost\": 30}").status()).isEqualTo(200);

        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(stock(tenant, rewardId)).isEqualTo(4);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
    }

    @Test
    void aRewardTheMemberCannotAffordIsRefusedAndWritesNothing() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 30L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);

        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(redeemedTotal(tenant)).isZero();
        assertThat(vouchers(tenant)).isZero();
        assertThat(stock(tenant, rewardId)).isEqualTo(5);
        assertThat(available(tenant, "M-1")).isEqualTo(30);
    }

    @Test
    void aMemberInDebtIsRefusedBeforeAnyRewardIsTaken() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);
        assertThat(redeemReward(tenant, "M-1", "K-1", rewardId).status()).isEqualTo(201);

        // Returning the whole order claws the 100 points it granted back while only 60 are left, so the shortfall is debt.
        assertThat(putReturn(tenant, "O-M-1", "R-1", returnBody("2026-03-20T12:00:00Z", java.math.BigDecimal.ONE,
                        10_000))
                .status())
                .isEqualTo(201);
        assertThat(debt(tenant, "M-1")).as("the clawback outran what the member still held").isPositive();

        ApiResponse refused = redeemReward(tenant, "M-1", "K-2", rewardId);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("MEMBER_IN_DEBT");
        assertThat(stock(tenant, rewardId)).isEqualTo(4);
        assertThat(vouchers(tenant)).isEqualTo(1);
    }

    /**
     * The reward is read without a lock and refused before anything is written, so an archived reward costs the member
     * nothing. The committed contract names {@code 422 REWARD_NOT_AVAILABLE} for it, which is what the answer carries.
     */
    @Test
    void aRewardThatIsNotOnOfferIsRefusedAsInvalid() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);
        assertThat(patchReward(tenant, rewardId, "\"1\"", "{\"active\": false}").status()).isEqualTo(200);

        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("REWARD_NOT_AVAILABLE");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(vouchers(tenant)).isZero();
        assertThat(stock(tenant, rewardId)).isEqualTo(5);
        assertThat(available(tenant, "M-1")).isEqualTo(100);
    }

    @Test
    void anUnknownRewardOrMemberIsNotFound() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);

        ApiResponse unknownReward = redeemReward(tenant, "M-1", "K-1", UUID.randomUUID().toString());
        ApiResponse unknownMember = redeemReward(tenant, "M-nobody", "K-2", rewardId);
        ApiResponse noReward = redeemReward(tenant, "M-1", "K-3", null);

        assertThat(unknownReward.status()).isEqualTo(404);
        assertThat(unknownReward.code()).isEqualTo("REWARD_NOT_FOUND");
        assertThat(unknownMember.status()).isEqualTo(404);
        assertThat(unknownMember.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(noReward.status()).as("a redemption without a reward is a request the app cannot apply")
                .isEqualTo(422);
        assertThat(noReward.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(scalar(tenant, "select count(*) from rewards")).as("no refused call wrote").isEqualTo(1);
        assertThat(stock(tenant, rewardId)).isEqualTo(5);
    }

    @Test
    void aRedemptionWithoutAnIdempotencyKeyIsRefused() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);

        ApiResponse refused = redeemReward(tenant, "M-1", null, rewardId);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(stock(tenant, rewardId)).isEqualTo(5);
    }

    @Test
    void anExhaustedStockIsRefusedAndGivesTheSpentPointsBack() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Last one", 40L, 0L, null);

        ApiResponse refused = redeemReward(tenant, "M-1", "K-1", rewardId);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("OUT_OF_STOCK");

        // The spend happened before the conditional decrement that found no row, and the whole transaction went back:
        // no redemption, no REDEEM entry, no voucher, no stock movement.
        assertThat(scalar(tenant, "select count(*) from redemptions")).as("the spend rolled back with the redemption")
                .isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM'")).isZero();
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots"))
                .as("the lots were given back")
                .isEqualTo(100);
        assertThat(vouchers(tenant)).isZero();
        assertThat(stock(tenant, rewardId)).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(100);
    }

    /**
     * The same rollback, forced the other way: the redemption itself succeeds and the caller's transaction then fails, so
     * the spend, the stock unit and the voucher must all disappear together.
     */
    @Test
    void aRedemptionWhoseTransactionFailsLeavesNothingBehind() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, null);

        Runnable redemptionInAFailingTransaction = () -> {
            assertThat(redeemReward(tenant, "M-1", "K-1", rewardId).status()).isEqualTo(201);
            throw new IllegalStateException("the request could not be committed");
        };
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> inTenant(tenant, () -> unitOfWork.transactional(redemptionInAFailingTransaction)))
                .isInstanceOf(IllegalStateException.class));

        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM'")).isZero();
        assertThat(vouchers(tenant)).isZero();
        assertThat(stock(tenant, rewardId)).as("the stock unit went back with the transaction").isEqualTo(5);
        assertThat(outboxRows(tenant, "voucher.issued")).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(100);

        // Outside the rollback the same call does all three, so the silence above is the rollback's.
        assertThat(redeemReward(tenant, "M-1", "K-1", rewardId).status()).isEqualTo(201);
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, rewardId)).isEqualTo(4);
    }

    @Test
    void anUnlimitedStockIsNeverExhaustedAndReversalsCannotGoBelowZero() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Unlimited", 10L, null, null);

        for (int i = 0; i < 3; i++) {
            assertThat(redeemReward(tenant, "M-1", "K-" + i, rewardId).status()).isEqualTo(201);
        }

        ApiResponse read = getReward(tenant, rewardId);
        assertThat(read.json().get("stock").isNull()).as("an unlimited reward holds no stock column").isTrue();
        assertThat(vouchers(tenant)).isEqualTo(3);
        assertThat(redeemedTotal(tenant)).isEqualTo(30);
        assertThat(available(tenant, "M-1")).isEqualTo(70);
    }
}
