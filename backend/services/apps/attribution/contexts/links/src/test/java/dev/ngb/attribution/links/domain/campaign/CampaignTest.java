package dev.ngb.attribution.links.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A campaign's legal values, its default model and window, and the currency that cannot change once measured. */
class CampaignTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private final TenantId tenantId = new TenantId(UUID.randomUUID());

    @Test
    void aNewCampaignDefaultsToLastAndThirtyDays() {
        Campaign campaign = Campaign.create(
                UUID.randomUUID(), tenantId, "Spring", "spring-sale", null, null, 0, "USD", null, null, NOW);
        assertThat(campaign.model()).isEqualTo(AttributionModel.LAST);
        assertThat(campaign.windowDays()).isEqualTo(Campaign.DEFAULT_WINDOW_DAYS);
        assertThat(campaign.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(campaign.version()).isEqualTo(1);
    }

    @Test
    void aWindowOutsideOneToNinetyDaysIsRefused() {
        assertThatThrownBy(() -> Campaign.create(
                        UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 91, "USD",
                        null, null, NOW))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Campaign.create(
                        UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, -1, "USD",
                        null, null, NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aMadeUpCurrencyIsRefused() {
        assertThatThrownBy(() -> Campaign.create(
                        UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "XYZ",
                        null, null, NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        assertThatThrownBy(() -> Campaign.create(
                        UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "USD",
                        LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void changingTheCurrencyOnceMeasuredIsRefused() {
        Campaign campaign = Campaign.create(
                UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "USD", null, null,
                NOW);
        assertThatThrownBy(() -> campaign.changeCurrency("EUR", true, NOW.plusSeconds(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void changingTheCurrencyBeforeAnythingIsMeasuredIsAllowed() {
        Campaign campaign = Campaign.create(
                UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "USD", null, null,
                NOW);
        Campaign changed = campaign.changeCurrency("eur", false, NOW.plusSeconds(1));
        assertThat(changed.currency()).isEqualTo("EUR");
        assertThat(changed.version()).isEqualTo(2);
    }

    @Test
    void aModelOrWindowChangeIsReportedSoARecomputeStarts() {
        Campaign campaign = Campaign.create(
                UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "USD", null, null,
                NOW);
        assertThat(campaign.changesModel(AttributionModel.LINEAR, 30)).isTrue();
        assertThat(campaign.changesModel(AttributionModel.LAST, 7)).isTrue();
        assertThat(campaign.changesModel(AttributionModel.LAST, 30)).isFalse();
    }

    @Test
    void anArchivedCampaignCanNoLongerBeEdited() {
        Campaign campaign = Campaign.create(
                UUID.randomUUID(), tenantId, "X", "spring-sale", null, AttributionModel.LAST, 30, "USD", null, null,
                NOW);
        Campaign archived = campaign.archive(NOW.plusSeconds(1));
        assertThat(archived.status()).isEqualTo(CampaignStatus.ARCHIVED);
        assertThatThrownBy(() -> archived.update("New", null, AttributionModel.LAST, 30, null, null, NOW))
                .isInstanceOf(ConflictException.class);
    }
}
