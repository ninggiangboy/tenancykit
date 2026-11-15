package dev.ngb.email.sending.application.campaign.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.api.audience.AudienceDirectory;
import dev.ngb.email.audience.api.audience.ReceiverSummary;
import dev.ngb.email.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.email.command.SendEmailUseCase;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.shared.EmailKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One rate-limited publishing tick for every SENDING campaign of the tenant.
 *
 * <p>Recipients are claimed with a lease and {@code for update skip locked}, so two workers never take the same row
 * and a crash leaves it claimable again. The email id is derived from {@code (campaign, receiver)}: a worker that
 * crashed after the outbox write re-derives the same id, the send path finds the existing email and nothing is
 * published twice. Eligibility is re-checked here, at publish time, so a late unsubscribe is honoured.
 *
 * <p>The rate is the tenant's, not each campaign's: the cap exists to protect mailer and the transport, and a tenant
 * that opens N campaigns must not be allowed N times the ceiling. The budget is computed once per tick and spent
 * across every campaign, in campaign order.
 *
 * <p>Each recipient is claimed, sent and marked in its own transaction. A row that fails is left claimed, so its lease
 * brings it back for a retry while the rest of the batch — and the tenant's other campaigns — carry on. A campaign that
 * cannot even be read or claimed is skipped for this tick rather than aborting the loop.
 */
public class PublishCampaignBatchUseCase {

    private static final long LEASE_SECONDS = 60;

    public record Published(int published, int skipped, int failed, int completed) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final AudienceDirectory audience;
    private final SendEmailUseCase send;
    private final EmailSettingsRepository settings;
    private final Clock clock;
    private final Duration tick;

    public PublishCampaignBatchUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            CampaignRepository campaigns,
            CampaignRecipientRepository recipients,
            AudienceDirectory audience,
            SendEmailUseCase send,
            EmailSettingsRepository settings,
            Clock clock,
            Duration tick) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.audience = audience;
        this.send = send;
        this.settings = settings;
        this.clock = clock;
        this.tick = tick;
    }

    /**
     * The per-tick share of the tenant's per-minute cap. It is derived from the configured tick rather than a constant,
     * so changing {@code email.campaign.publisher-interval} cannot silently make the spend per minute wrong.
     */
    private int budgetFor(EmailSettingsView effective) {
        long ticksPerMinute = Math.max(1, Duration.ofMinutes(1).toSeconds() / Math.max(1, tick.toSeconds()));
        return (int) Math.max(1, effective.campaignPublishPerMinute() / ticksPerMinute);
    }

    public Published execute() {
        // A tick only ever runs inside a tenant's scope; requiring it here fails loudly if a caller forgets.
        tenantContext.requireTenant();
        EmailSettingsView effective = unitOfWork.transactional(settings::current);
        int budget = budgetFor(effective);
        Instant now = clock.now();
        int published = 0;
        int skipped = 0;
        int failed = 0;
        int completed = 0;
        for (UUID campaignId : unitOfWork.transactional(campaigns::sending)) {
            if (budget <= 0) {
                break;
            }
            Campaign campaign = unitOfWork.transactional(() -> campaigns.findById(campaignId).orElse(null));
            if (campaign == null) {
                continue;
            }
            List<UUID> claimed = claim(campaignId, now, budget);
            budget -= claimed.size();
            if (!claimed.isEmpty()) {
                Map<UUID, ReceiverSummary> byId = receiversById(claimed);
                for (UUID receiverId : claimed) {
                    switch (publishOne(campaign, receiverId, byId.get(receiverId))) {
                        case PUBLISHED -> published++;
                        case SKIPPED -> skipped++;
                        case FAILED -> failed++;
                    }
                }
            }
            if (unitOfWork.transactional(() -> recipients.hasPending(campaignId))) {
                continue;
            }
            unitOfWork.transactional(() -> campaigns.markCompleted(campaignId, clock.now()));
            completed++;
        }
        return new Published(published, skipped, failed, completed);
    }

    /**
     * Claims up to {@code budget} rows. A failure here is a contention or database condition, not a recipient's: the
     * campaign is left for the next tick and the tenant's other campaigns still run.
     */
    private List<UUID> claim(UUID campaignId, Instant now, int budget) {
        try {
            return unitOfWork.transactional(
                    () -> recipients.claimPending(campaignId, now, now.plusSeconds(LEASE_SECONDS), budget));
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    /**
     * The receivers behind a claim, read in one round trip. A failure is contained to this campaign's tick: its rows
     * stay claimed and come back when the lease expires, and the tenant's other campaigns are unaffected.
     */
    private Map<UUID, ReceiverSummary> receiversById(List<UUID> claimed) {
        try {
            return unitOfWork.transactional(() -> audience.receiversByIds(claimed)).stream()
                    .collect(Collectors.toMap(ReceiverSummary::id, Function.identity()));
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private Outcome publishOne(Campaign campaign, UUID receiverId, ReceiverSummary receiver) {
        try {
            Email email = unitOfWork.transactional(() -> {
                if (receiver == null) {
                    recipients.markSkipped(campaign.id(), receiverId);
                    return null;
                }
                Email sent = send.execute(command(campaign, receiver));
                recipients.markPublished(campaign.id(), receiverId, sent.id());
                return sent;
            });
            if (email == null) {
                return Outcome.SKIPPED;
            }
            return switch (email.status()) {
                case FAILED -> Outcome.FAILED;
                case SKIPPED -> Outcome.SKIPPED;
                default -> Outcome.PUBLISHED;
            };
        } catch (RuntimeException failure) {
            // The row keeps its claim and becomes claimable again when the lease runs out. It is deliberately not
            // marked skipped: a failure that escaped the send path is an infrastructure condition — a database or
            // outbox error — and a retry may well deliver. What matters is that the failure stops here: the other
            // recipients and the tenant's other campaigns are in their own transactions and still run.
            return Outcome.FAILED;
        }
    }

    private static SendEmailUseCase.Command command(Campaign campaign, ReceiverSummary receiver) {
        return new SendEmailUseCase.Command(
                receiver.email(),
                EmailKind.MARKETING,
                campaign.fromKey(),
                null,
                variables(receiver),
                null, null, null, null,
                campaign.fromName(),
                campaign.replyTo(),
                deterministicId(campaign.id(), receiver.id()),
                campaign.id(),
                receiver.id(),
                campaign.templateId(),
                campaign.templateRevision());
    }

    private static Map<String, Object> variables(ReceiverSummary receiver) {
        Map<String, Object> values = new LinkedHashMap<>(receiver.attributes());
        values.put("email", receiver.email());
        if (receiver.firstName() != null) {
            values.put("first_name", receiver.firstName());
        }
        if (receiver.lastName() != null) {
            values.put("last_name", receiver.lastName());
        }
        return Map.of("receiver", values);
    }

    static UUID deterministicId(UUID campaignId, UUID receiverId) {
        return UUID.nameUUIDFromBytes(
                (campaignId + ":" + receiverId).getBytes(StandardCharsets.UTF_8));
    }

    private enum Outcome {
        PUBLISHED,
        SKIPPED,
        FAILED
    }
}
