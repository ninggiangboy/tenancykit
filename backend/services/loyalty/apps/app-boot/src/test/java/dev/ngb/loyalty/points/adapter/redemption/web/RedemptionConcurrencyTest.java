package dev.ngb.loyalty.points.adapter.redemption.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What many callers at once do to one member's points.
 *
 * <p>The member's row is the lock every spend and every clawback takes first, so the answers below are the ones the
 * wallet rule promises: no point is spent twice, one key spends once however often it is sent, and the balance a caller
 * reads afterwards is always the lots minus the debt.
 */
class RedemptionConcurrencyTest extends PointsApiTest {

    private static final String ORDERED_AT = "2026-03-02T10:00:00Z";
    private static final String RETURNED_AT = "2026-03-20T12:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));

    /** Four database connections are what the harness holds, so this is as wide as a burst can be without queueing. */
    private static final int WIDTH = 4;

    /** As many calls as the widest burst the harness can hold, which is what the spend of one point is stressed with. */
    private static final int CALLS = 100;

    @Test
    void oneHundredSpendsOfOnePointAgainstFiftySucceedExactlyFiftyTimes() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        assertThat(available(tenant, "M-1")).isEqualTo(50);

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH,
                index -> postRedemption(tenant, "M-1", "K-" + index, 1, "R-" + index));

        assertThat(answers).filteredOn(answer -> answer.status() == 201)
                .as("fifty of the hundred spends found a point")
                .hasSize(50);
        assertThat(answers).filteredOn(answer -> answer.status() == 409)
                .as("the rest were refused")
                .hasSize(50)
                .allSatisfy(answer -> assertThat(answer.code()).isEqualTo("INSUFFICIENT_POINTS"));
        assertThat(answers).as("no call answered anything else")
                .allSatisfy(answer -> assertThat(answer.status()).isIn(201, 409));
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(50);
        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'REDEEM'"))
                .isEqualTo(50);
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots")).isZero();
        assertThat(available(tenant, "M-1")).isZero();
    }

    @Test
    void oneHundredCallsCarryingOneKeySpendOnce() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));

        List<ApiResponse> answers = ParallelCalls.run(CALLS, WIDTH,
                index -> postRedemption(tenant, "M-1", "K-one", 1, "RECEIPT-1"));

        assertThat(answers).filteredOn(answer -> answer.status() == 201)
                .as("exactly one call spent")
                .hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 200)
                .as("the others replayed it")
                .hasSize(CALLS - 1);
        assertThat(answers.stream()
                        .map(answer -> answer.json().get("redemptionId").asString())
                        .collect(Collectors.toSet()))
                .as("every answer names the one redemption")
                .hasSize(1);
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'REDEEM'"))
                .isEqualTo(1);
        assertThat(available(tenant, "M-1")).as("one point was spent, not a hundred").isEqualTo(49);
    }

    @Test
    void aSpendRacingAClawbackLeavesTheBalanceAtLotsMinusDebt() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 10000));
        assertThat(available(tenant, "M-1")).isEqualTo(100);

        List<ApiResponse> answers = ParallelCalls.run(2, 2, index -> index == 0
                ? postRedemption(tenant, "M-1", "K-1", 60, "RECEIPT-1")
                : putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 10000)));

        assertThat(answers).as("the return always answers").anySatisfy(answer -> assertThat(answer.status()).isEqualTo(201));
        assertThat(answers).as("the spend is either made or refused, never half made")
                .anySatisfy(answer -> assertThat(answer.status()).isIn(201, 409));
        long lots = scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots where expires_at > now()");
        long debt = scalar(tenant, "select coalesce(debt, 0) from members where external_ref = 'M-1'");
        assertThat(debt)
                .as("a clawback the lots could not cover in full leaves exactly the shortfall as debt")
                .isIn(0L, 60L);
        assertThat(available(tenant, "M-1"))
                .as("the balance is the lots minus the debt, whichever order the two calls took")
                .isEqualTo(lots - debt);
        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'REDEEM'"))
                .as("the points spent are either the sixty or none of them")
                .isIn(0L, 60L);
    }

    @Test
    void aMemberInDebtIsRefusedUntilTheNextEarnRepaysIt() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));
        postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");
        assertThat(putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000)).status())
                .isEqualTo(201);
        assertThat(debt(tenant, "M-1")).as("the clawback found no lot to take and became debt").isEqualTo(10);
        assertThat(available(tenant, "M-1")).isEqualTo(-10);

        ApiResponse owed = postRedemption(tenant, "M-1", "K-2", 1, "RECEIPT-2");
        List<ApiResponse> burst = ParallelCalls.run(4, WIDTH,
                index -> postRedemption(tenant, "M-1", "K-burst-" + index, 1, null));

        assertThat(owed.status()).isEqualTo(409);
        assertThat(owed.code()).isEqualTo("MEMBER_IN_DEBT");
        assertThat(burst).as("a burst changes nothing while the member owes points")
                .allSatisfy(answer -> assertThat(answer.code()).isEqualTo("MEMBER_IN_DEBT"));
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);

        // The next earn repays the debt first, so what is left becomes a lot the member can spend.
        assertThat(putOrder(tenant, "O-2", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 2500)).status())
                .isEqualTo(201);
        assertThat(debt(tenant, "M-1")).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(15);

        ApiResponse paid = postRedemption(tenant, "M-1", "K-3", 5, "RECEIPT-3");

        assertThat(paid.status()).as("the member owes nothing now").isEqualTo(201);
        assertThat(paid.json().get("available").asLong()).isEqualTo(10);
        assertThat(paid.json().get("debt").asLong()).isZero();
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }
}
