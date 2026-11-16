package dev.ngb.loyalty.tiers.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.program.api.tier.TierView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The window a tier is decided over and the tier that window gives.
 *
 * <p>Every case here is a boundary the calendar can put a member on: a spend exactly at a threshold, an order exactly as
 * old as the window, a month that is shorter than the one a member is subtracted from, and a zone whose midnight is not
 * UTC's. They are the cases where an off-by-one moves a member's tier, so they are asserted to the second.
 */
class TierPolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId HONOLULU = ZoneId.of("Pacific/Honolulu");

    private static final List<TierView> TIERS = List.of(
            new TierView("BRONZE", "Bronze", 0L, new BigDecimal("1.00"), 0),
            new TierView("SILVER", "Silver", 100_000L, new BigDecimal("1.50"), 1),
            new TierView("GOLD", "Gold", 500_000L, new BigDecimal("2.00"), 2));

    @Test
    void theTierIsTheHighestThresholdTheSpendReaches() {
        TierPolicy policy = new TierPolicy(12, UTC);
        Instant now = Instant.parse("2026-06-15T12:00:00Z");

        assertThat(policy.choose(TIERS, spend(now, 99_999L), now).tierCode())
                .as("one minor unit short of silver")
                .isEqualTo("BRONZE");
        assertThat(policy.choose(TIERS, spend(now, 100_000L), now).tierCode())
                .as("exactly at the threshold")
                .isEqualTo("SILVER");
        assertThat(policy.choose(TIERS, spend(now, 499_999L), now).tierCode()).isEqualTo("SILVER");
        assertThat(policy.choose(TIERS, spend(now, 500_000L), now).tierCode()).isEqualTo("GOLD");
        assertThat(policy.choose(TIERS, spend(now, 100_000L), now).qualifyingSpend()).isEqualTo(100_000L);
    }

    @Test
    void anOrderExactlyAsOldAsTheWindowNoLongerCounts() {
        TierPolicy policy = new TierPolicy(12, UTC);
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        Instant boundary = Instant.parse("2025-06-15T12:00:00Z");

        assertThat(policy.windowStart(now)).isEqualTo(boundary);
        assertThat(policy.qualifyingSpend(List.of(new TierPolicy.Spend(boundary, 100_000L)), now))
                .as("an order at exactly the boundary has aged out")
                .isZero();
        assertThat(policy.qualifyingSpend(List.of(new TierPolicy.Spend(boundary.plusSeconds(1), 100_000L)), now))
                .as("one second younger and it still counts")
                .isEqualTo(100_000L);
        assertThat(policy.choose(TIERS, List.of(new TierPolicy.Spend(boundary, 100_000L)), now).reviewAt())
                .as("nothing is inside the window, so no instant changes the answer")
                .isNull();
    }

    @Test
    void aWindowThatStartsOnTheThirtyFirstClampsToTheShorterMonth() {
        TierPolicy policy = new TierPolicy(1, UTC);

        assertThat(policy.windowStart(Instant.parse("2026-03-31T10:00:00Z")))
                .as("31 March minus one month is 28 February")
                .isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
        assertThat(policy.windowStart(Instant.parse("2024-03-31T10:00:00Z")))
                .as("in a leap year it is 29 February")
                .isEqualTo(Instant.parse("2024-02-29T10:00:00Z"));
        assertThat(policy.windowStart(Instant.parse("2026-05-31T00:00:00Z")))
                .as("31 May minus one month is 30 April")
                .isEqualTo(Instant.parse("2026-04-30T00:00:00Z"));

        TierPolicy.Choice choice = policy.choose(
                TIERS, List.of(new TierPolicy.Spend(Instant.parse("2026-02-28T09:59:59Z"), 100_000L)),
                Instant.parse("2026-03-31T10:00:00Z"));
        assertThat(choice.tierCode()).as("an order before the clamped start has aged out").isEqualTo("BRONZE");
        assertThat(choice.reviewAt()).isNull();
    }

    @Test
    void anOrderOnADayTheNextMonthLacksLeavesWhenTheWindowEntersThatMonth() {
        TierPolicy policy = new TierPolicy(1, UTC);

        assertThat(policy.leavesAt(Instant.parse("2026-01-31T10:00:00Z")))
                .as("February has no 31st, so the order leaves when March starts")
                .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(policy.leavesAt(Instant.parse("2026-02-15T10:00:00Z")))
                .as("a day the next month has keeps its own time")
                .isEqualTo(Instant.parse("2026-03-15T10:00:00Z"));
    }

    @Test
    void aZoneWhoseMidnightDiffersFromUtcDecidesTheMonth() {
        Instant now = Instant.parse("2026-03-01T05:00:00Z");
        TierPolicy inHonolulu = new TierPolicy(1, HONOLULU);
        TierPolicy inUtc = new TierPolicy(1, UTC);
        // 30 January is still January in Honolulu's window, which starts on 29 January there; in UTC the window already
        // starts on 1 February.
        TierPolicy.Spend order = new TierPolicy.Spend(Instant.parse("2026-01-30T00:00:00Z"), 100_000L);

        assertThat(inHonolulu.windowStart(now)).isEqualTo(Instant.parse("2026-01-29T05:00:00Z"));
        assertThat(inUtc.windowStart(now)).isEqualTo(Instant.parse("2026-02-01T05:00:00Z"));
        assertThat(inHonolulu.choose(TIERS, List.of(order), now).tierCode())
                .as("the order is inside the zone's window")
                .isEqualTo("SILVER");
        assertThat(inUtc.choose(TIERS, List.of(order), now).tierCode())
                .as("the same order is outside the UTC window")
                .isEqualTo("BRONZE");

        // Its review is when that order leaves the zone's window: 30 January plus one month is February, which has no
        // 30th, so the window next starts on 1 March there, which is the next day in UTC.
        assertThat(inHonolulu.choose(TIERS, List.of(order), now).reviewAt())
                .isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }

    @Test
    void nothingInsideTheWindowGivesTheLowestTierAndNoReview() {
        TierPolicy policy = new TierPolicy(12, UTC);
        Instant now = Instant.parse("2026-06-15T12:00:00Z");

        TierPolicy.Choice choice = policy.choose(TIERS, List.of(), now);

        assertThat(choice.tierCode()).as("the lowest tier starts at nothing, so a member sits in it").isEqualTo("BRONZE");
        assertThat(choice.qualifyingSpend()).isZero();
        assertThat(choice.reviewAt()).isNull();
    }

    @Test
    void theReviewIsWhenTheOldestCountedOrderAgesOut() {
        TierPolicy policy = new TierPolicy(12, UTC);
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        Instant oldest = Instant.parse("2025-09-01T08:00:00Z");

        TierPolicy.Choice choice = policy.choose(
                TIERS,
                List.of(new TierPolicy.Spend(Instant.parse("2026-02-01T00:00:00Z"), 60_000L),
                        new TierPolicy.Spend(oldest, 60_000L)),
                now);

        assertThat(choice.qualifyingSpend()).isEqualTo(120_000L);
        assertThat(choice.tierCode()).isEqualTo("SILVER");
        assertThat(choice.reviewAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void aProgramWithNoTiersGivesNoTier() {
        TierPolicy policy = new TierPolicy(12, UTC);
        Instant now = Instant.parse("2026-06-15T12:00:00Z");

        TierPolicy.Choice choice = policy.choose(List.of(), spend(now, 100_000L), now);

        assertThat(choice.tierCode()).isNull();
        assertThat(choice.qualifyingSpend()).isEqualTo(100_000L);
    }

    private static List<TierPolicy.Spend> spend(Instant at, long amount) {
        return List.of(new TierPolicy.Spend(at.minusSeconds(3_600), amount));
    }
}
