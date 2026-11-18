package dev.ngb.mailroom.sending.application.campaign.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.audience.api.audience.AudienceDirectory;
import dev.ngb.mailroom.audience.api.audience.ReceiverSummary;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRepository;
import dev.ngb.mailroom.sending.application.email.command.SendEmailUseCase;
import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;
import dev.ngb.mailroom.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import dev.ngb.mailroom.sending.domain.campaign.CampaignStatus;
import dev.ngb.mailroom.sending.domain.campaign.CampaignTargetType;
import dev.ngb.mailroom.sending.domain.email.Email;
import dev.ngb.mailroom.sending.domain.email.EmailStatus;
import dev.ngb.mailroom.shared.EmailKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The publishing tick's two bounds, proved with fakes rather than a database.
 *
 * <p>The rate is the tenant's, not each campaign's: one tenant with several campaigns must not send N times the
 * ceiling that exists to protect mailer and the transport. And one recipient that fails must be marked and skipped,
 * not hold the campaign — or the tick — for ever.
 */
class PublishCampaignBatchUseCaseTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final FakeRecipients recipients = new FakeRecipients();
    private final FakeCampaigns campaigns = new FakeCampaigns().recipients(recipients);
    private final FakeAudience audience = new FakeAudience();
    private final FakeSend send = new FakeSend();
    private final RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork();

    @Test
    void theBudgetIsTheTenantsNotEachCampaigns() {
        // 12 ticks of five seconds per minute, so 12 per minute is one per tick; two campaigns of two recipients each
        // must still publish only one email.
        UUID first = campaigns.add(recipients.add(2));
        UUID second = campaigns.add(recipients.add(2));

        PublishCampaignBatchUseCase.Published published = publisher(12).execute();

        assertThat(published.published()).as("the tick spent the tenant's whole budget").isEqualTo(1);
        assertThat(send.commands).hasSize(1);
        assertThat(recipients.pending(first) + recipients.pending(second)).isEqualTo(3);
        assertThat(recipients.published).isEqualTo(1);
    }

    @Test
    void aLargerBudgetIsSpentAcrossCampaignsInOrder() {
        campaigns.add(recipients.add(1));
        campaigns.add(recipients.add(1));

        PublishCampaignBatchUseCase.Published published = publisher(24).execute();

        assertThat(published.published()).isEqualTo(2);
        assertThat(send.commands).hasSize(2);
    }

    @Test
    void oneFailingRecipientDoesNotStopTheOnesAfterIt() {
        UUID campaign = campaigns.add(recipients.add(3));
        UUID failing = recipients.byCampaign.get(campaign).get(1);
        send.refuse(failing);

        PublishCampaignBatchUseCase.Published published = publisher(120).execute();

        assertThat(published.published()).isEqualTo(2);
        assertThat(published.failed()).isEqualTo(1);
        assertThat(recipients.pending(campaign))
                .as("only the row whose transaction failed stays pending, for its lease to retry")
                .isEqualTo(1);
        assertThat(recipients.byCampaign.get(campaign)).containsExactly(failing);
    }

    @Test
    void aSnapshotRowWhoseReceiverIsGoneIsSkippedNotFailed() {
        UUID campaign = campaigns.add(recipients.add(2));
        audience.failing.add(recipients.byCampaign.get(campaign).getFirst());

        PublishCampaignBatchUseCase.Published published = publisher(120).execute();

        assertThat(published.published()).isEqualTo(1);
        assertThat(published.failed()).isZero();
        assertThat(published.skipped()).isEqualTo(1);
        assertThat(recipients.pending(campaign)).isZero();
    }

    @Test
    void aCampaignWhoseWholeBatchFailsIsNotMarkedCompleted() {
        UUID campaign = campaigns.add(recipients.add(2));
        send.failEverything = true;

        PublishCampaignBatchUseCase.Published published = publisher(120).execute();

        assertThat(published.failed()).isEqualTo(2);
        assertThat(published.completed()).isZero();
        assertThat(campaigns.completed).as("a campaign with rows still pending cannot complete").isEmpty();
        assertThat(recipients.pending(campaign)).isEqualTo(2);
    }

    // --------------------------------------------------------------- fakes

    private PublishCampaignBatchUseCase publisher(int perMinute) {
        return new PublishCampaignBatchUseCase(
                unitOfWork,
                new FakeTenantContext(),
                campaigns,
                recipients,
                audience,
                send,
                new FakeSettings(perMinute),
                () -> NOW,
                Duration.ofSeconds(5));
    }

    /** The transaction seam, run inline: each call is one transaction, which is all the budget logic observes. */
    private static final class RecordingUnitOfWork implements UnitOfWork {

        @Override
        public <T> T transactional(java.util.function.Supplier<T> work) {
            return work.get();
        }
    }

    private static final class FakeTenantContext implements TenantContext {

        @Override
        public RequestScope current() {
            return RequestScope.tenant(TENANT);
        }

        @Override
        public <T> T callAs(RequestScope scope, java.util.function.Supplier<T> work) {
            return work.get();
        }
    }

    private record FakeSettings(int perMinute) implements EmailSettingsRepository {

        @Override
        public EmailSettingsView current() {
            return new EmailSettingsView("noreply", null, null, 30, perMinute, 0);
        }

        @Override
        public EmailSettingsView save(EmailSettingsView settings) {
            throw new UnsupportedOperationException();
        }
    }

    /** Campaigns keyed by id; the recipient store is handed the same ids so a claim and a mark meet on one row. */
    private static final class FakeCampaigns implements CampaignRepository {

        private final Map<UUID, Campaign> byId = new LinkedHashMap<>();
        private final List<UUID> completed = new ArrayList<>();

        UUID add(List<UUID> receivers) {
            UUID id = UUID.randomUUID();
            byId.put(id, new Campaign(
                    id,
                    TENANT,
                    "Launch",
                    CampaignStatus.SENDING,
                    UUID.randomUUID(),
                    1,
                    CampaignTargetType.GROUP,
                    UUID.randomUUID(),
                    "noreply",
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    1L,
                    NOW,
                    NOW));
            recipients.snapshot(id, TENANT, receivers);
            return id;
        }

        private FakeRecipients recipients;

        FakeCampaigns recipients(FakeRecipients recipients) {
            this.recipients = recipients;
            return this;
        }

        @Override
        public void insert(Campaign campaign) {
            byId.put(campaign.id(), campaign);
        }

        @Override
        public java.util.Optional<Campaign> findById(UUID campaignId) {
            return java.util.Optional.ofNullable(byId.get(campaignId));
        }

        @Override
        public boolean update(Campaign campaign, long expectedVersion) {
            byId.put(campaign.id(), campaign);
            return true;
        }

        @Override
        public boolean tryStartScheduled(UUID campaignId, Instant now) {
            return false;
        }

        @Override
        public List<UUID> dueScheduled(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<UUID> sending() {
            return byId.values().stream()
                    .filter(campaign -> campaign.status() == CampaignStatus.SENDING)
                    .map(Campaign::id)
                    .toList();
        }

        @Override
        public void markCompleted(UUID campaignId, Instant now) {
            completed.add(campaignId);
        }
    }

    private static final class FakeRecipients implements CampaignRecipientRepository {

        private final Map<UUID, List<UUID>> byCampaign = new HashMap<>();
        private final List<UUID> claimed = new ArrayList<>();
        private int published;
        private int skipped;

        List<UUID> add(int count) {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(UUID.randomUUID());
            }
            return ids;
        }

        @Override
        public void snapshot(UUID campaignId, TenantId tenantId, List<UUID> receiverIds) {
            byCampaign.computeIfAbsent(campaignId, key -> new ArrayList<>()).addAll(receiverIds);
        }

        @Override
        public List<UUID> claimPending(UUID campaignId, Instant now, Instant leaseUntil, int limit) {
            // A claimed row leaves the pending set, as the real lease does until the send resolves it.
            List<UUID> claimedNow = byCampaign.getOrDefault(campaignId, List.of()).stream()
                    .filter(id -> !claimed.contains(id))
                    .limit(limit)
                    .toList();
            claimed.addAll(claimedNow);
            return claimedNow;
        }

        @Override
        public void markPublished(UUID campaignId, UUID receiverId, UUID emailId) {
            resolve(campaignId, receiverId);
            published++;
        }

        @Override
        public void markSkipped(UUID campaignId, UUID receiverId) {
            resolve(campaignId, receiverId);
            skipped++;
        }

        private void resolve(UUID campaignId, UUID receiverId) {
            byCampaign.getOrDefault(campaignId, List.of()).remove(receiverId);
            claimed.remove(receiverId);
        }

        @Override
        public boolean hasPending(UUID campaignId) {
            return !byCampaign.getOrDefault(campaignId, List.of()).isEmpty();
        }

        @Override
        public long count(UUID campaignId, dev.ngb.mailroom.sending.domain.campaign.RecipientState state) {
            return 0;
        }

        @Override
        public long countAll(UUID campaignId) {
            return 0;
        }

        @Override
        public int deleteCompletedBefore(Instant completedBefore, int limit) {
            return 0;
        }

        int pending(UUID campaignId) {
            return byCampaign.getOrDefault(campaignId, List.of()).size();
        }
    }

    private static final class FakeAudience implements AudienceDirectory {

        private final Map<UUID, ReceiverSummary> byId = new LinkedHashMap<>();
        private final java.util.Set<UUID> failing = new java.util.HashSet<>();

        @Override
        public List<ReceiverSummary> pageGroup(UUID groupId, UUID afterReceiverId, int limit) {
            return List.of();
        }

        @Override
        public List<ReceiverSummary> pageSegment(UUID segmentId, UUID afterReceiverId, int limit) {
            return List.of();
        }

        @Override
        public List<ReceiverSummary> receiversByIds(List<UUID> receiverIds) {
            return receiverIds.stream()
                    .filter(id -> !failing.contains(id))
                    .map(id -> byId.computeIfAbsent(
                            id, key -> new ReceiverSummary(key, key + "@example.test", null, null, Map.of())))
                    .toList();
        }
    }

    private static final class FakeSend extends SendEmailUseCase {

        private final List<SendEmailUseCase.Command> commands = new ArrayList<>();
        private final java.util.Set<UUID> refusing = new java.util.HashSet<>();
        private boolean failEverything;

        FakeSend() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        void refuse(UUID receiverId) {
            refusing.add(receiverId);
        }

        @Override
        public Email execute(SendEmailUseCase.Command command) {
            if (failEverything || refusing.contains(command.receiverId())) {
                throw new IllegalStateException("the recipient cannot be prepared");
            }
            commands.add(command);
            EmailStatus status = EmailStatus.QUEUED;
            return new Email(
                    command.emailId(),
                    TENANT,
                    EmailKind.MARKETING,
                    command.campaignId(),
                    command.receiverId(),
                    command.to(),
                    command.fromKey(),
                    null,
                    null,
                    "Hi",
                    null,
                    null,
                    status,
                    null,
                    command.emailId().toString(),
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW);
        }
    }
}
