package dev.ngb.core.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The daily visitor hash: stable within a day, different across days, and different for a different tenant, address or
 * agent. The value is 16 bytes base64url, so a column never holds more than 22 characters.
 */
class DailyVisitorHashTest {

    private static final String KEY = "a-deployment-visitor-hash-key";

    private final DailyVisitorHash hash = new DailyVisitorHash(KEY);
    private final LocalDate day = LocalDate.of(2026, 3, 10);

    @Test
    void theSameVisitOnTheSameDayHashesTheSame() {
        assertThat(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day))
                .isEqualTo(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day));
    }

    @Test
    void theSameVisitOnTheNextDayHashesDifferently() {
        assertThat(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day))
                .isNotEqualTo(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day.plusDays(1)));
    }

    @Test
    void aDifferentTenantHashesDifferentlySoIdenticalVisitorsNeverShare() {
        assertThat(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day))
                .isNotEqualTo(hash.of("tenant-b", "203.0.113.7", "Mozilla/5.0", day));
    }

    @Test
    void aDifferentAddressOrAgentHashesDifferently() {
        String baseline = hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day);
        assertThat(hash.of("tenant-a", "203.0.113.8", "Mozilla/5.0", day)).isNotEqualTo(baseline);
        assertThat(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.1", day)).isNotEqualTo(baseline);
    }

    @Test
    void theHashIsSixteenBytesOfBase64Url() {
        String value = hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day);
        assertThat(value).hasSize(DailyVisitorHash.LENGTH);
        assertThat(value).matches("^[A-Za-z0-9_-]{22}$");
    }

    @Test
    void aDifferentMasterKeyProducesADifferentHash() {
        DailyVisitorHash other = new DailyVisitorHash("another-deployment-key");
        assertThat(other.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day))
                .isNotEqualTo(hash.of("tenant-a", "203.0.113.7", "Mozilla/5.0", day));
    }

    @Test
    void aNullAddressOrAgentIsAcceptedBecauseARedirectStillHasOne() {
        assertThat(hash.of("tenant-a", null, null, day)).isNotNull();
    }

    @Test
    void aBlankKeyIsRefused() {
        assertThatThrownBy(() -> new DailyVisitorHash(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingTenantIsRefused() {
        assertThatThrownBy(() -> hash.of(null, "203.0.113.7", "Mozilla/5.0", day))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
