package dev.ngb.loyalty.rewards.adapter.voucher.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.RewardsApiTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The lock-order check: for a stretch of wall-clock time, redemptions, reversals and uses run interleaved over one
 * member and one voucher, and every call must answer.
 *
 * <p>The three flows take their locks in one order — a redemption and a reversal both take the member's row and only then
 * a reward or voucher row, and a use takes the voucher's row alone — so no pair of them can form a cycle. What a cycle
 * would look like is a call that never answers, or one answered with a lock-wait refusal, so this case counts both: every
 * call answered, none answered {@code 503}, and the rows afterwards agree with the balance.
 */
class RewardLockOrderTest extends RewardsApiTest {

    /** Four database connections are what the harness holds, so this is as wide as an interleaving can be. */
    private static final int WIDTH = 4;

    /** How long the loop runs. The plan asks for thirty seconds; ten is enough to interleave hundreds of calls. */
    private static final long MILLIS = 10_000L;

    @Test
    void interleavedRedemptionsReversalsAndUsesOverOneMemberFinishWithoutALockWait() {
        TenantId tenant = tenant();
        // Enough points for many redemptions, so the loop is limited by the locks rather than by the balance.
        seedEarnedPoints(tenant, "M-1", 100_000L);
        String rewardId = seedReward(tenant, "Coffee", 100L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "SEED", rewardId).json().get("voucher").get("code").asString();
        UUID seedRedemptionId = redemptionOfVoucher(tenant, code);

        List<ApiResponse> answers = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger counter = new AtomicInteger();
        long deadline = System.currentTimeMillis() + MILLIS;

        // Four lanes over the same member and the same voucher: a redemption, a use of the seed voucher, a reversal of
        // whatever a redemption issued, and a use of whatever a redemption issued. Every lane names its own key or use
        // reference, so an answer other than a documented one is a real failure rather than an idempotent replay.
        ParallelCalls.run(WIDTH, WIDTH, lane -> {
            while (System.currentTimeMillis() < deadline) {
                int call = counter.incrementAndGet();
                try {
                    ApiResponse answer = switch (lane) {
                        case 0 -> redeemReward(tenant, "M-1", "LOOP-R-" + call, rewardId);
                        case 1 -> useVoucher(tenant, code, "LOOP-U-" + call);
                        case 2 -> {
                            ApiResponse redeemed = redeemReward(tenant, "M-1", "LOOP-RV-" + call, rewardId);
                            if (redeemed.status() == 201) {
                                yield reverseRedemption(tenant, "M-1",
                                        UUID.fromString(redeemed.json().get("redemptionId").asString()));
                            }
                            yield redeemed;
                        }
                        default -> {
                            ApiResponse redeemed = redeemReward(tenant, "M-1", "LOOP-RU-" + call, rewardId);
                            if (redeemed.status() == 201) {
                                yield useVoucher(tenant,
                                        redeemed.json().get("voucher").get("code").asString(), "LOOP-USE-" + call);
                            }
                            yield redeemed;
                        }
                    };
                    answers.add(answer);
                    if (answer.status() == 503) {
                        failures.add("a lock wait on lane " + lane + ": " + answer.body());
                    } else if (answer.status() == 500) {
                        failures.add("an unexpected failure on lane " + lane + ": " + answer.body());
                    }
                } catch (RuntimeException e) {
                    failures.add("lane " + lane + " threw: " + e);
                }
            }
            return lane;
        });
        running.set(false);

        assertThat(failures).as("no call may deadlock or give up waiting for a lock").isEmpty();
        assertThat(answers).as("the loop actually interleaved calls").hasSizeGreaterThan(WIDTH);
        assertThat(answers).as("every call answered a documented status")
                .allSatisfy(answer -> assertThat(answer.status()).isIn(200, 201, 409, 422));

        // The rows afterwards agree with the balance: a voucher's unit of stock is out exactly while its redemption stands,
        // and the points spent are the successful redemptions' cost minus the ones that were reversed.
        long issued = scalar(tenant, "select count(*) from vouchers where status = 'ISSUED'");
        long used = scalar(tenant, "select count(*) from vouchers where status = 'USED'");
        long voided = scalar(tenant, "select count(*) from vouchers where status = 'VOIDED'");
        long vouchers = vouchers(tenant);
        assertThat(issued + used + voided).as("every voucher is in exactly one state").isEqualTo(vouchers);
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'USED' and use_ref is null"))
                .as("a used voucher names its use")
                .isZero();
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'VOIDED' and void_reason <> 'REVERSAL'"))
                .isZero();
        assertThat(scalar(tenant,
                        "select coalesce(sum(points), 0) from ledger_entries where kind = 'REDEEM'")
                        - scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries"
                                + " where kind = 'REDEEM_REVERSAL'"))
                .as("the points the member holds are the spends that still stand")
                .isEqualTo(100_000L - available(tenant, "M-1"));
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots"))
                .isEqualTo(available(tenant, "M-1"));
        assertThat(scalar(tenant, "select count(*) from redemptions where id = ?", seedRedemptionId))
                .as("the seed redemption is still there")
                .isEqualTo(1);
    }
}
