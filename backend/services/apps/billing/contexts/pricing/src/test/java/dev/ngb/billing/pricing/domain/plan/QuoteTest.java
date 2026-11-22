package dev.ngb.billing.pricing.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.pricing.domain.rule.PriceLine;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a quote says each metric cost, which a metric-scoped coupon discounts and nothing else. */
class QuoteTest {

    private static final Currency USD = Currency.of("USD");

    private static Quote quote(long amount, PriceLine... lines) {
        return new Quote(Money.of(amount, USD), new BigDecimal(amount), List.of(lines), Quote.Adjustment.NONE);
    }

    @Test
    void sumsEachMetricsLinesAndRoundsOnce() {
        Quote quote = quote(1_000,
                new PriceLine("first band", "api_calls", new BigDecimal("100"), new BigDecimal("300.4")),
                new PriceLine("second band", "api_calls", new BigDecimal("50"), new BigDecimal("200.4")),
                new PriceLine("storage", "storage", new BigDecimal("10"), new BigDecimal("499.2")));

        assertThat(quote.metricAmounts()).containsOnly(
                java.util.Map.entry("api_calls", Money.of(501, USD)),
                java.util.Map.entry("storage", Money.of(499, USD)));
    }

    @Test
    void aLineWithoutAMetricBelongsToNoMetric() {
        Quote quote = quote(600,
                new PriceLine("start fee", null, null, new BigDecimal("100")),
                new PriceLine("blank metric", " ", null, new BigDecimal("100")),
                new PriceLine("calls", "api_calls", new BigDecimal("1"), new BigDecimal("400")));

        assertThat(quote.metricAmounts()).containsOnlyKeys("api_calls");
    }
}
