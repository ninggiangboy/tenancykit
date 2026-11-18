package dev.ngb.billing.pricing.domain.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The default event count and the addition of two inputs of one metric, which rating reads for every request. */
class MeasureTest {

    @Test
    void anOmittedEventCountIsOneForAReportedQuantity() {
        Measure measure = Measure.of(new BigDecimal("2.5"), null);

        assertThat(measure.events()).isEqualTo(1);
        assertThat(measure.quantity()).isEqualByComparingTo("2.5");
    }

    @Test
    void anOmittedEventCountIsNoneForZero() {
        assertThat(Measure.of(BigDecimal.ZERO, null).events()).isZero();
    }

    @Test
    void aReportedEventCountIsKept() {
        assertThat(Measure.of(new BigDecimal("2.5"), 7L).events()).isEqualTo(7);
    }

    @Test
    void twoInputsOfTheSameMetricAddUp() {
        Measure merged = Measure.of(new BigDecimal("2"), 1L).plus(Measure.of(new BigDecimal("3"), 2L));

        assertThat(merged.quantity()).isEqualByComparingTo("5");
        assertThat(merged.events()).isEqualTo(3);
    }
}
