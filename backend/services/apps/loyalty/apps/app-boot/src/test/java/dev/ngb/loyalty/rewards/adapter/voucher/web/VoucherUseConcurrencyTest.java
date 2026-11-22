package dev.ngb.loyalty.rewards.adapter.voucher.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.RewardsApiTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What many callers at once do to one voucher and to one reward's stock.
 *
 * <p>The lock order is what makes these answers true: a redemption takes the member's row and only then the reward's, so
 * two redemptions of one member run one after the other and the stock decrement matches {@code stock > 0} — the loser
 * takes no row and its whole transaction, spend included, unwinds. A use locks only the voucher's row, so it can never
 * form a cycle with either.
 */
class VoucherUseConcurrencyTest extends RewardsApiTest {

    /** Four database connections are what the harness holds, so this is as wide as a burst can be without queueing. */
    private static final int WIDTH = 4;

    /** As many calls as the widest burst the harness can hold, which is what the one-use rule is stressed with. */
    private static final int CALLS = 100;

    @Test
    void oneHundredParallelUsesOfOneVoucherSucceedExactlyOnce() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH, index -> useVoucher(tenant, code, "USE-" + index));

        assertThat(answers).filteredOn(answer -> answer.status() == 200)
                .as("exactly one call consumed the voucher")
                .hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 409)
                .as("the rest were refused")
                .hasSize(CALLS - 1)
                .allSatisfy(answer -> assertThat(answer.code()).isEqualTo("VOUCHER_USED"));
        assertThat(answers).as("no call answered anything else")
                .allSatisfy(answer -> assertThat(answer.status()).isIn(200, 409));
        assertThat(answers.stream()
                        .filter(answer -> answer.status() == 200)
                        .map(answer -> answer.json().get("useRef").asString())
                        .collect(Collectors.toSet()))
                .as("the one success names the reference that wrote it")
                .hasSize(1);
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'USED'")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from vouchers where used_at is not null")).isEqualTo(1);
        assertThat(outboxRows(tenant, "voucher.used")).as("only the writing call announced a use").isEqualTo(1);
    }

    @Test
    void oneHundredParallelRedemptionsOfAStockOfTenIssueExactlyTenVouchers() {
        TenantId tenant = tenant();
        // 2000 points would buy twenty of a reward costing 100, so the stock of ten is the only bound in this burst.
        seedEarnedPoints(tenant, "M-1", 2000L);
        String rewardId = seedReward(tenant, "Limited", 100L, 10L, 7);

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH,
                index -> redeemReward(tenant, "M-1", "K-" + index, rewardId));

        assertThat(answers).filteredOn(answer -> answer.status() == 201)
                .as("exactly ten redemptions took a unit")
                .hasSize(10);
        assertThat(answers).filteredOn(answer -> answer.status() == 409)
                .as("the rest found no unit")
                .hasSize(CALLS - 10)
                .allSatisfy(answer -> assertThat(answer.code()).isEqualTo("OUT_OF_STOCK"));
        assertThat(answers).as("no call answered anything else")
                .allSatisfy(answer -> assertThat(answer.status()).isIn(201, 409));

        // The failures rolled their spend back with them: ten spends, ten vouchers, ten units, nothing left.
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(10);
        assertThat(redeemedTotal(tenant)).as("the points were spent exactly ten times").isEqualTo(1000);
        assertThat(vouchers(tenant)).as("every successful redemption issued exactly one voucher").isEqualTo(10);
        assertThat(scalar(tenant, "select count(distinct code) from vouchers")).isEqualTo(10);
        assertThat(stock(tenant, rewardId)).isZero();
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isEqualTo(1000);
        assertThat(available(tenant, "M-1")).isEqualTo(1000);
        assertThat(outboxRows(tenant, "voucher.issued")).as("a rolled-back redemption announced nothing").isEqualTo(10);
    }

    @Test
    void oneHundredParallelRedemptionsCarryingOneKeyIssueOneVoucher() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 1000L);
        String rewardId = seedReward(tenant, "Limited", 100L, 10L, 7);

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH,
                index -> redeemReward(tenant, "M-1", "K-one", rewardId));

        assertThat(answers).filteredOn(answer -> answer.status() == 201)
                .as("exactly one call spent and issued")
                .hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 200)
                .as("the others replayed it")
                .hasSize(CALLS - 1);
        assertThat(answers.stream()
                        .map(answer -> answer.json().get("voucher").get("voucherId").asString())
                        .collect(Collectors.toSet()))
                .as("every answer names the one voucher")
                .hasSize(1);
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(redeemedTotal(tenant)).isEqualTo(100);
        assertThat(vouchers(tenant)).isEqualTo(1);
        assertThat(stock(tenant, rewardId)).as("the replays took no stock").isEqualTo(9);
        assertThat(available(tenant, "M-1")).isEqualTo(900);
    }

    /**
     * The same reference on a voucher two callers race for: the first writes and the rest replay it, because the second
     * caller sees what the first committed under the same row lock rather than deciding the voucher is free.
     */
    @Test
    void oneHundredParallelUsesCarryingOneReferenceAllAnswerWithTheSameUse() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH, index -> useVoucher(tenant, code, "USE-one"));

        assertThat(answers).as("the same reference replays the same result")
                .allSatisfy(answer -> assertThat(answer.status()).isEqualTo(200));
        assertThat(answers.stream()
                        .map(answer -> answer.json().get("usedAt").asString())
                        .collect(Collectors.toSet()))
                .as("every answer names the one instant the voucher was used at")
                .hasSize(1);
        assertThat(answers.stream().map(answer -> answer.json().get("useRef").asString()).collect(Collectors.toSet()))
                .containsExactly("USE-one");
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'USED'")).isEqualTo(1);
        assertThat(outboxRows(tenant, "voucher.used")).as("one use happened, so one event").isEqualTo(1);
    }

    /**
     * A use racing a reversal of the redemption that issued the voucher: the use locks the voucher's row and the reversal
     * locks the member's and then the voucher's, so one of them goes first and the other answers what it finds. Either
     * way the voucher ends in exactly one state and the stock matches it.
     */
    @Test
    void aUseRacingTheReversalOfItsRedemptionLeavesOneConsistentOutcome() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 1000L);
        String rewardId = seedReward(tenant, "Coffee", 100L, 10L, 7);
        ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        String code = redeemed.json().get("voucher").get("code").asString();
        UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());

        List<ApiResponse> answers = ParallelCalls.run(2, 2, index -> index == 0
                ? useVoucher(tenant, code, "USE-1")
                : reverseRedemption(tenant, "M-1", redemptionId));

        ApiResponse use = answers.get(0);
        ApiResponse reversal = answers.get(1);

        assertThat(use.status()).as("the use either consumed the voucher or was refused " + use.body()).isIn(200, 409);
        assertThat(reversal.status()).as("the reversal either voided it or refused " + reversal.body())
                .isIn(200, 409);

        String state = getVoucher(tenant, code).json().get("state").asString();
        long stock = stock(tenant, rewardId);
        if ("USED".equals(state)) {
            assertThat(use.status()).isEqualTo(200);
            assertThat(reversal.code()).isEqualTo("VOUCHER_ALREADY_USED");
            assertThat(stock).as("a used voucher's unit stays taken").isEqualTo(9);
            assertThat(available(tenant, "M-1")).isEqualTo(900);
        } else {
            assertThat(state).isEqualTo("VOIDED");
            assertThat(reversal.status()).isEqualTo(200);
            assertThat(use.status()).isEqualTo(409);
            assertThat(stock).as("a voided voucher's unit came back").isEqualTo(10);
            assertThat(available(tenant, "M-1")).isEqualTo(1000);
        }
        assertThat(scalar(tenant, "select count(*) from vouchers")).isEqualTo(1);
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots"))
                .as("the balance and the lots always agree")
                .isEqualTo(available(tenant, "M-1"));
    }
}
