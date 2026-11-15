package dev.ngb.email.sending.application.campaign.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.campaign.service.CampaignSnapshotter;
import dev.ngb.email.sending.domain.campaign.Campaign;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The scheduler's blast radius.
 *
 * <p>Each campaign starts in its own transaction, so one that cannot be started — its segment was deleted after it was
 * scheduled, its audience is too large — is logged and left for an operator. A single wrapping transaction would
 * re-run the same failure every tick and hold every other scheduled campaign of the tenant behind it.
 */
class AdvanceCampaignsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final CountingUnitOfWork unitOfWork = new CountingUnitOfWork();
    private final FakeCampaigns campaigns = new FakeCampaigns().unitOfWork(unitOfWork);
    private final FakeSnapshotter snapshotter = new FakeSnapshotter();

    @Test
    void oneUnstartableCampaignDoesNotStopTheOthers() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        campaigns.due = List.of(broken, healthy);
        snapshotter.refuse(broken);

        int started = useCase().startDue(10);

        assertThat(started).as("only the campaign whose snapshot succeeded counts").isEqualTo(1);
        assertThat(campaigns.committed)
                .as("the broken campaign's start is rolled back; the healthy one is committed")
                .containsExactly(healthy);
        assertThat(snapshotter.snapshotted).containsExactly(healthy);
    }

    @Test
    void everyCampaignGetsItsOwnTransaction() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        campaigns.due = List.of(first, second);

        useCase().startDue(10);

        // One read for the due list, plus one transaction per campaign: a shared transaction is what made one failure
        // roll back the whole tick.
        assertThat(unitOfWork.transactions).isEqualTo(3);
    }

    @Test
    void aCampaignAnotherWorkerClaimedIsNotStartedTwice() {
        UUID claimedElsewhere = UUID.randomUUID();
        campaigns.due = List.of(claimedElsewhere);
        campaigns.claimable = false;

        int started = useCase().startDue(10);

        assertThat(started).isZero();
        assertThat(snapshotter.snapshotted).isEmpty();
    }

    private AdvanceCampaignsUseCase useCase() {
        return new AdvanceCampaignsUseCase(unitOfWork, campaigns, snapshotter, () -> NOW);
    }

    private static final class CountingUnitOfWork implements UnitOfWork {

        private int transactions;
        private final List<Runnable> undo = new ArrayList<>();

        @Override
        public <T> T transactional(Supplier<T> work) {
            transactions++;
            int before = undo.size();
            try {
                return work.get();
            } catch (RuntimeException failure) {
                // A real transaction rolls the campaign's start back; the fake must too, or the test would pass on a
                // committed start that a deployment would never keep.
                for (int i = undo.size() - 1; i >= before; i--) {
                    undo.get(i).run();
                }
                throw failure;
            }
        }

        void onRollback(Runnable action) {
            undo.add(action);
        }
    }

    private static final class FakeCampaigns implements CampaignRepository {

        private List<UUID> due = List.of();
        private boolean claimable = true;
        private final List<UUID> committed = new ArrayList<>();

        private CountingUnitOfWork unitOfWork;

        FakeCampaigns unitOfWork(CountingUnitOfWork unitOfWork) {
            this.unitOfWork = unitOfWork;
            return this;
        }

        @Override
        public void insert(Campaign campaign) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Campaign> findById(UUID campaignId) {
            return Optional.of(new Campaign(
                    campaignId, new dev.ngb.core.id.TenantId(UUID.randomUUID()), "Launch",
                    dev.ngb.email.sending.domain.campaign.CampaignStatus.SENDING, UUID.randomUUID(), 1,
                    dev.ngb.email.sending.domain.campaign.CampaignTargetType.GROUP, UUID.randomUUID(),
                    "noreply", null, null, null, NOW, null, 1L, NOW, NOW));
        }

        @Override
        public boolean update(Campaign campaign, long expectedVersion) {
            return true;
        }

        @Override
        public boolean tryStartScheduled(UUID campaignId, Instant now) {
            if (!claimable) {
                return false;
            }
            // A committed start is what a later tick sees; a rolled-back one is not, so the fake honours both.
            committed.add(campaignId);
            unitOfWork.onRollback(() -> committed.remove(campaignId));
            return true;
        }

        @Override
        public List<UUID> dueScheduled(Instant now, int limit) {
            return due;
        }

        @Override
        public List<UUID> sending() {
            return List.of();
        }

        @Override
        public void markCompleted(UUID campaignId, Instant now) {
        }
    }

    private static final class FakeSnapshotter extends CampaignSnapshotter {

        private final List<UUID> snapshotted = new ArrayList<>();
        private UUID refusing;

        FakeSnapshotter() {
            super(null, null);
        }

        void refuse(UUID campaignId) {
            refusing = campaignId;
        }

        @Override
        public long snapshot(Campaign campaign) {
            if (campaign.id().equals(refusing)) {
                throw new NotFoundException("No segment exists here.");
            }
            snapshotted.add(campaign.id());
            return 1;
        }
    }
}
