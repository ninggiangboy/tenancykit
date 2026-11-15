package dev.ngb.email.sending.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The state machine is the contract: every illegal (state, action) pair answers a conflict. */
class CampaignTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private static Campaign draft() {
        return Campaign.create(
                UUID.randomUUID(), TENANT, "Launch", UUID.randomUUID(), 1,
                CampaignTargetType.GROUP, UUID.randomUUID(), "noreply", null, null, NOW);
    }

    @Test
    void theHappyPathReachesCompleted() {
        Campaign scheduled = draft().schedule(NOW.plusSeconds(60), NOW);
        assertThat(scheduled.status()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(scheduled.due(NOW)).isFalse();
        assertThat(scheduled.due(NOW.plusSeconds(61))).isTrue();

        Campaign sending = scheduled.start(NOW);
        assertThat(sending.status()).isEqualTo(CampaignStatus.SENDING);
        assertThat(sending.startedAt()).isEqualTo(NOW);

        Campaign paused = sending.pause(NOW);
        assertThat(paused.status()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(paused.resume(NOW).status()).isEqualTo(CampaignStatus.SENDING);
    }

    @Test
    void aDraftCanBeSentImmediately() {
        assertThat(draft().start(NOW).status()).isEqualTo(CampaignStatus.SENDING);
    }

    @Test
    void onlyADraftCanBeEditedOrScheduled() {
        Campaign sending = draft().start(NOW);
        assertThatThrownBy(() -> sending.schedule(NOW, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> sending.withDetails(
                        "Other", UUID.randomUUID(), 1, CampaignTargetType.GROUP, UUID.randomUUID(), "noreply",
                        null, null, NOW))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void pauseAndResumeAreOnlyLegalFromTheirOwnState() {
        assertThatThrownBy(() -> draft().pause(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> draft().resume(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> draft().start(NOW).start(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aTerminalCampaignCannotBeCancelled() {
        Campaign cancelled = draft().cancel(NOW);
        assertThat(cancelled.status()).isEqualTo(CampaignStatus.CANCELLED);
        assertThat(cancelled.completedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> cancelled.cancel(NOW)).isInstanceOf(ConflictException.class);
    }
}
