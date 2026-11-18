package dev.ngb.loyalty.points.domain.earn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The earning rule, one property at a time: a single {@code HALF_EVEN} rounding at the end, the best matching promotion per
 * line, the tier multiplier, the program's zone for windows and weekdays, and a recompute that reads nothing but the
 * stored snapshot.
 *
 * <p>The calculator is pure, so these are plain assertions about numbers: no database, no clock, no framework.
 */
class EarnCalculatorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Instant MARCH = Instant.parse("2026-03-02T15:30:00Z");
    private static final BigDecimal BRONZE = new BigDecimal("1.00");

    private final EarnCalculator calculator = new EarnCalculator();

    @Test
    void theBaseRateDividesTheEligibleAmountsByTheProgramsUnit() {
        EarnOutcome outcome = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                List.of(line(1, "SKU-1", "GROCERY", 4500), line(2, "SKU-2", "GROCERY", 1500)), List.of()));

        assertThat(outcome.points()).isEqualTo(60);
        assertThat(outcome.spendAmount()).isEqualTo(6000);
        assertThat(outcome.contributions()).extracting(LineContribution::multiplier)
                .extracting(java.math.BigDecimal::stripTrailingZeros)
                .containsExactly(BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void theRoundingHappensOnceAtTheEndAndIsHalfEven() {
        assertThat(points(250)).as("2.5 rounds to the even neighbour 2").isEqualTo(2);
        assertThat(points(350)).as("3.5 rounds to the even neighbour 4").isEqualTo(4);
        assertThat(points(150)).isEqualTo(2);
        assertThat(points(50)).isZero();
        assertThat(points(450)).isEqualTo(4);
        assertThat(points(349)).as("3.49 is below the halfway point").isEqualTo(3);
    }

    @Test
    void roundingPerLineWouldGiveADifferentAnswerWhichIsWhyItIsNotDone() {
        // 1.5 + 1.0 rounds to 2 + 1 = 3 when each line is rounded; the rule rounds their sum instead.
        assertThat(points(150, 100)).isEqualTo(2);
        assertThat(points(150, 100)).isEqualTo(
                calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                        List.of(line(1, "SKU-1", "GROCERY", 150), line(2, "SKU-2", "GROCERY", 100)), List.of()))
                        .points());
    }

    @Test
    void anIneligibleLineContributesNeitherPointsNorSpend() {
        EarnOutcome outcome = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                List.of(
                        line(1, "SKU-1", "GROCERY", 5000),
                        ineligible(2, "SKU-2", "GIFT_CARD", 3000),
                        line(3, "SKU-3", "GROCERY", 2000)),
                List.of()));

        assertThat(outcome.points()).isEqualTo(70);
        assertThat(outcome.spendAmount()).isEqualTo(7000);
        assertThat(outcome.contributions()).extracting(LineContribution::eligibleAmount)
                .containsExactly(5000L, 0L, 2000L);
    }

    @Test
    void theBestMatchingPromotionWinsAndItsIdIsRecorded() {
        PromotionView produce = promotion("promo-produce", new BigDecimal("2.00"), 0, null, "PRODUCE", null, null,
                null, MARCH);
        PromotionView apple = promotion("promo-apple", new BigDecimal("3.00"), 0, "SKU-APPLE", null, null, null,
                null, MARCH);

        EarnOutcome outcome = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-03",
                List.of(line(1, "SKU-APPLE", "PRODUCE", 1000), line(2, "SKU-BANANA", "PRODUCE", 1000)),
                List.of(produce, apple)));

        assertThat(outcome.points()).isEqualTo(50);
        assertThat(outcome.contributions()).extracting(LineContribution::multiplier)
                .extracting(java.math.BigDecimal::stripTrailingZeros)
                .containsExactly(new BigDecimal("3"), new BigDecimal("2"));
        assertThat(outcome.snapshot().multiplierOf(1)).isEqualByComparingTo("3.00");
        assertThat(outcome.snapshot().lines().getFirst().promotionIds()).containsExactly(apple.promotionId());
    }

    @Test
    void anEqualMultiplierIsBrokenByPriorityAndThenByTheSmallerId() {
        PromotionView lower = promotion("promo-b", new BigDecimal("2.00"), 1, null, "PRODUCE", null, null, null,
                MARCH);
        PromotionView higher = promotion("promo-c", new BigDecimal("2.00"), 5, null, null, "ST-03", null, null, MARCH);

        EarnOutcome byPriority = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-03",
                List.of(line(1, "SKU-1", "PRODUCE", 1000)), List.of(lower, higher)));
        assertThat(byPriority.snapshot().lines().getFirst().promotionIds())
                .containsExactly(higher.promotionId());

        PromotionView samePriorityFirstId = promotion("promo-a", new BigDecimal("2.00"), 5, null, "PRODUCE", null,
                null, null, MARCH);
        EarnOutcome byId = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-03",
                List.of(line(1, "SKU-1", "PRODUCE", 1000)), List.of(higher, samePriorityFirstId)));
        assertThat(byId.snapshot().lines().getFirst().promotionIds().getFirst())
                .isEqualByComparingTo(smaller(higher, samePriorityFirstId));
    }

    @Test
    void theWindowIsComparedAsTheInstantItIsAndIsInclusive() {
        PromotionView june = new PromotionView(
                UUID.randomUUID(), "june", 0, PromotionStatus.ACTIVE, new BigDecimal("2.00"),
                Instant.parse("2026-06-30T04:00:00Z"), Instant.parse("2026-07-01T03:59:59Z"),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, null, NEW_YORK, 1L);

        EarnOutcome inside = calculator.evaluate(context(100, BRONZE, "BRONZE",
                Instant.parse("2026-07-01T02:30:00Z"), "ST-05", List.of(line(1, "SKU-1", "GROCERY", 1000)),
                List.of(june)));
        EarnOutcome after = calculator.evaluate(context(100, BRONZE, "BRONZE",
                Instant.parse("2026-07-01T05:00:00Z"), "ST-05", List.of(line(1, "SKU-1", "GROCERY", 1000)),
                List.of(june)));

        assertThat(inside.points()).isEqualTo(20);
        assertThat(after.points()).as("the window closed in the program's zone").isEqualTo(10);
    }

    @Test
    void theWeekdayIsTheLocalOneInTheProgramsZone() {
        PromotionView weekend = promotion("promo-weekend", new BigDecimal("2.00"), 0, null, null, null, null,
                List.of(6, 7), MARCH);
        PromotionView saturdayAndSunday = new PromotionView(
                weekend.promotionId(), "promo-weekend", 0, PromotionStatus.ACTIVE, weekend.multiplier(), null, null,
                List.of(), List.of(), List.of(), List.of(6, 7), List.of(), null, null, NEW_YORK, 1L);

        // 2026-06-07T01:00Z is Saturday 21:00 in New York, and already Sunday in UTC.
        EarnOutcome saturday = calculator.evaluate(context(100, BRONZE, "BRONZE",
                Instant.parse("2026-06-07T01:00:00Z"), "ST-06", List.of(line(1, "SKU-1", "GROCERY", 1000)),
                List.of(saturdayAndSunday)));
        EarnOutcome monday = calculator.evaluate(context(100, BRONZE, "BRONZE",
                Instant.parse("2026-06-08T14:00:00Z"), "ST-06", List.of(line(1, "SKU-1", "GROCERY", 1000)),
                List.of(saturdayAndSunday)));

        assertThat(saturday.points()).isEqualTo(20);
        assertThat(monday.points()).isEqualTo(10);
    }

    @Test
    void aLocalTimeRangeIsReadInTheProgramsZoneToo() {
        PromotionView evening = new PromotionView(
                UUID.randomUUID(), "evening", 0, PromotionStatus.ACTIVE, new BigDecimal("2.00"), null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), LocalTime.of(18, 0), LocalTime.of(23, 0),
                NEW_YORK, 1L);

        // 2026-03-02T23:30Z is 18:30 in New York, inside the range; the same instant read in UTC would not be.
        EarnOutcome eveningInNewYork = calculator.evaluate(context(100, BRONZE, "BRONZE",
                Instant.parse("2026-03-02T23:30:00Z"), "ST-01", List.of(line(1, "SKU-1", "GROCERY", 1000)),
                List.of(evening)));
        EarnOutcome midday = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of(evening)));

        assertThat(eveningInNewYork.points()).isEqualTo(20);
        assertThat(midday.points()).isEqualTo(10);
    }

    @Test
    void aStoreLimitedPromotionAppliesOnlyInItsStore() {
        PromotionView flagship = promotion("promo-flagship", new BigDecimal("2.00"), 0, null, null, "ST-12", null,
                null, MARCH);

        assertThat(calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-12",
                        List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of(flagship)))
                .points())
                .isEqualTo(20);
        assertThat(calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-99",
                        List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of(flagship)))
                .points())
                .isEqualTo(10);
    }

    @Test
    void theTierMultiplierMultipliesEveryLine() {
        EarnOutcome gold = calculator.evaluate(context(100, new BigDecimal("2.00"), "GOLD", MARCH, "ST-08",
                List.of(line(1, "SKU-1", "GROCERY", 2000)), List.of()));

        assertThat(gold.points()).isEqualTo(40);
        assertThat(gold.snapshot().tierCode()).isEqualTo("GOLD");
        assertThat(gold.snapshot().multiplierOf(1)).isEqualByComparingTo("2.00");
    }

    @Test
    void aTierAndAPromotionMultiplyTogether() {
        PromotionView double2 = promotion("promo-double", new BigDecimal("2.00"), 0, null, "PRODUCE", null, null,
                null, MARCH);

        EarnOutcome outcome = calculator.evaluate(context(100, new BigDecimal("1.50"), "SILVER", MARCH, "ST-03",
                List.of(line(1, "SKU-1", "PRODUCE", 1000)), List.of(double2)));

        assertThat(outcome.snapshot().multiplierOf(1)).isEqualByComparingTo("3.00");
        assertThat(outcome.points()).isEqualTo(30);
    }

    @Test
    void aReturnedAmountIsNotWorthPoints() {
        OrderLine partiallyReturned = new OrderLine(
                1, "SKU-1", "GROCERY", new BigDecimal("4"), 4000, true, new BigDecimal("2"), 1500);

        EarnOutcome outcome = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                List.of(partiallyReturned), List.of()));

        assertThat(outcome.points()).isEqualTo(25);
        assertThat(outcome.spendAmount()).isEqualTo(2500);
    }

    @Test
    void aRecomputeReadsTheSnapshotAndNotTheRulesOfTheDay() {
        PromotionView produce3 = promotion("promo-produce-3x", new BigDecimal("3.00"), 0, null, "PRODUCE", null,
                null, null, MARCH);
        EarnOutcome earned = calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-11",
                List.of(line(1, "SKU-1", "PRODUCE", 2000), line(2, "SKU-2", "OTHER", 1000)), List.of(produce3)));
        assertThat(earned.points()).isEqualTo(70);

        OrderLine line1 = new OrderLine(1, "SKU-1", "PRODUCE", BigDecimal.ONE, 2000, true, BigDecimal.ONE, 1000);
        OrderLine line2 = new OrderLine(2, "SKU-2", "OTHER", BigDecimal.ONE, 1000, true, BigDecimal.ONE, 1000);
        EarnOutcome replayed = calculator.recompute(earned.snapshot(), List.of(line1, line2));

        assertThat(replayed.points()).as("(2000-1000)*3 + (1000-1000)*1 = 3000 / 100").isEqualTo(30);
        assertThat(70 - replayed.points()).isEqualTo(40);
        assertThat(replayed.snapshot()).isSameAs(earned.snapshot());
    }

    @Test
    void aLineTheSnapshotDoesNotNameFallsBackToTheTierMultiplier() {
        EarnOutcome earned = calculator.evaluate(context(100, new BigDecimal("2.00"), "GOLD", MARCH, "ST-01",
                List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of()));

        EarnOutcome replayed = calculator.recompute(earned.snapshot(),
                List.of(line(9, "SKU-9", "GROCERY", 1000)));

        assertThat(replayed.contributions().getFirst().multiplier()).isEqualByComparingTo("2.00");
        assertThat(replayed.points()).isEqualTo(20);
    }

    @Test
    void nothingEarnsWhenTheProgramHasNoTiersAndNothingMatches() {
        EarnOutcome outcome = calculator.evaluate(context(100, BigDecimal.ONE, null, MARCH, "ST-01",
                List.of(line(1, "SKU-1", "GROCERY", 999)), List.of()));

        assertThat(outcome.points()).isEqualTo(10);
        assertThat(outcome.snapshot().tierCode()).isNull();
    }

    @Test
    void aMultiplierWithMoreThanTwoFractionDigitsIsNotAProgramValue() {
        assertThatThrownBy(() -> context(100, new BigDecimal("1.005"), "GOLD", MARCH, "ST-01",
                        List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two fraction digits");
    }

    @Test
    void aUnitOfZeroIsRefused() {
        assertThatThrownBy(() -> context(0, BRONZE, "BRONZE", MARCH, "ST-01",
                        List.of(line(1, "SKU-1", "GROCERY", 1000)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private long points(long amount) {
        return calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                        List.of(line(1, "SKU-1", "GROCERY", amount)), List.of()))
                .points();
    }

    private long points(long first, long second) {
        return calculator.evaluate(context(100, BRONZE, "BRONZE", MARCH, "ST-01",
                        List.of(line(1, "SKU-1", "GROCERY", first), line(2, "SKU-2", "GROCERY", second)), List.of()))
                .points();
    }

    private static EarnContext context(
            long unitAmount,
            BigDecimal tierMultiplier,
            String tierCode,
            Instant occurredAt,
            String storeCode,
            List<OrderLine> lines,
            List<PromotionView> promotions) {
        return new EarnContext(unitAmount, "USD", tierCode, tierMultiplier, occurredAt, storeCode, "POS", lines,
                promotions);
    }

    private static OrderLine line(int lineNo, String sku, String category, long amount) {
        return OrderLine.sold(lineNo, sku, category, BigDecimal.ONE, amount, true);
    }

    private static OrderLine ineligible(int lineNo, String sku, String category, long amount) {
        return OrderLine.sold(lineNo, sku, category, BigDecimal.ONE, amount, false);
    }

    private static PromotionView promotion(
            String id,
            BigDecimal multiplier,
            int priority,
            String sku,
            String category,
            String storeCode,
            String channel,
            List<Integer> daysOfWeek,
            Instant at) {
        return new PromotionView(
                UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                id,
                priority,
                PromotionStatus.ACTIVE,
                multiplier,
                null,
                null,
                category == null ? List.of() : List.of(category),
                sku == null ? List.of() : List.of(sku),
                storeCode == null ? List.of() : List.of(storeCode),
                daysOfWeek == null ? List.of() : daysOfWeek,
                List.of(),
                null,
                null,
                NEW_YORK,
                1L);
    }

    private static UUID smaller(PromotionView first, PromotionView second) {
        return first.promotionId().compareTo(second.promotionId()) < 0 ? first.promotionId() : second.promotionId();
    }
}
