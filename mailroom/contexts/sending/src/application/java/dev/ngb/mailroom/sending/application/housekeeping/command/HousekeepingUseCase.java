package dev.ngb.mailroom.sending.application.housekeeping.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.mailroom.sending.application.email.port.EmailRepository;
import dev.ngb.mailroom.sending.application.email.port.EmailSettingsView;
import dev.ngb.mailroom.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.mailroom.sending.application.tracking.port.EmailLinkRepository;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes rendered bodies and their tracking links after the tenant's retention, and the snapshot rows of campaigns
 * that completed long enough ago. Metadata, events and statistics survive, so an old email still reads
 * {@code bodyPurged: true}.
 *
 * <p>It is bounded by a batch size and idempotent: a second run on the same rows finds nothing to purge.
 */
public class HousekeepingUseCase {

    public record Purged(int bodies, int links, int campaignRecipients) {}

    private final UnitOfWork unitOfWork;
    private final EmailSettingsRepository settings;
    private final EmailRepository emails;
    private final EmailLinkRepository links;
    private final CampaignRecipientRepository recipients;
    private final Clock clock;

    public HousekeepingUseCase(
            UnitOfWork unitOfWork,
            EmailSettingsRepository settings,
            EmailRepository emails,
            EmailLinkRepository links,
            CampaignRecipientRepository recipients,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.emails = emails;
        this.links = links;
        this.recipients = recipients;
        this.clock = clock;
    }

    public Purged execute(int batchSize, Duration campaignRecipientRetention) {
        Instant now = clock.now();
        Instant completedCutoff = now.minus(campaignRecipientRetention);
        return unitOfWork.transactional(() -> {
            EmailSettingsView effective = settings.current();
            Instant bodyCutoff = now.minus(Duration.ofDays(effective.bodyRetentionDays()));
            int bodies = emails.purgeBodies(bodyCutoff, now, batchSize);
            int purgedLinks = links.purgeForPurgedBodies(bodyCutoff, batchSize);
            int purgedRecipients = recipients.deleteCompletedBefore(completedCutoff, batchSize);
            return new Purged(bodies, purgedLinks, purgedRecipients);
        });
    }
}
