package dev.ngb.mailer.email.application.email.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.email.application.email.exception.UnresolvableSenderException;
import dev.ngb.mailer.email.application.email.port.ClaimedDelivery;
import dev.ngb.mailer.email.application.email.port.DeliveryClaimRepository;
import dev.ngb.mailer.email.application.email.port.DeliveryResult;
import dev.ngb.mailer.email.application.email.port.DeliveryResultPublisher;
import dev.ngb.mailer.email.application.email.port.EmailSender;
import dev.ngb.mailer.email.application.email.port.ExhaustedDelivery;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.email.application.email.port.SenderResolver;
import dev.ngb.mailer.email.application.email.service.DeliverySettings;
import dev.ngb.mailer.email.domain.delivery.DeliveryAttempt;
import dev.ngb.mailer.email.domain.delivery.DeliveryPolicy;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.email.domain.delivery.EmailDelivery;
import java.time.Instant;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * One delivery round for the current tenant: claim what is due, send it, record what came back.
 *
 * <p>The claim and the outcomes are separate short transactions and the sends happen between them, outside any
 * transaction. A worker that dies in between leaves its rows claimed, and they become due again when their lease runs
 * out, which is why delivery is at-least-once and why the transcript records an attempt rather than assuming one.
 *
 * <p>Each outcome is its own transaction: one rejected recipient must not roll back the outcomes — and the attempt
 * rows — of the other deliveries in the same claim.
 *
 * <p>A terminal outcome also publishes its result in that same transaction, so a crash cannot record an outcome and
 * lose the event that reports it. The publish is guarded by the compare-and-swap result: a worker whose row another
 * worker has taken over writes nothing and publishes nothing.
 */
public class DeliverEmailsUseCase {

    public record Round(int claimed, int succeeded, int retrying, int dead) {
        public static final Round NONE = new Round(0, 0, 0, 0);
    }

    private final UnitOfWork unitOfWork;
    private final DeliveryClaimRepository claims;
    private final SenderResolver resolver;
    private final EmailSender sender;
    private final DeliveryResultPublisher results;
    private final TenantContext tenantContext;
    private final DeliverySettings settings;
    private final Clock clock;
    private final DoubleSupplier random;

    public DeliverEmailsUseCase(
            UnitOfWork unitOfWork,
            DeliveryClaimRepository claims,
            SenderResolver resolver,
            EmailSender sender,
            DeliveryResultPublisher results,
            TenantContext tenantContext,
            DeliverySettings settings,
            Clock clock,
            DoubleSupplier random) {
        this.unitOfWork = unitOfWork;
        this.claims = claims;
        this.resolver = resolver;
        this.sender = sender;
        this.results = results;
        this.tenantContext = tenantContext;
        this.settings = settings;
        this.clock = clock;
        this.random = random;
    }

    public Round runOnce() {
        TenantId tenantId = tenantContext.requireTenant();
        DeliveryPolicy policy = settings.policy();
        List<ClaimedDelivery> claimed = unitOfWork.transactional(() -> {
            Instant now = clock.now();
            publishExhausted(tenantId, claims.killExhausted(now, policy.maxAttempts()));
            return claims.claimDue(now, settings.batchSize(), policy.lease());
        });
        if (claimed.isEmpty()) {
            return Round.NONE;
        }
        int succeeded = 0;
        int retrying = 0;
        int dead = 0;
        for (ClaimedDelivery item : claimed) {
            DeliveryStatus status = writeOutcome(tenantId, item, policy);
            if (status == DeliveryStatus.SUCCEEDED) {
                succeeded++;
            } else if (status == DeliveryStatus.DEAD) {
                dead++;
            } else {
                retrying++;
            }
        }
        return new Round(claimed.size(), succeeded, retrying, dead);
    }

    private DeliveryStatus writeOutcome(TenantId tenantId, ClaimedDelivery item, DeliveryPolicy policy) {
        Sent sent = send(tenantId, item);
        DeliveryAttempt attempt = sent.attempt();
        int attemptsMade = item.delivery().attempts();
        DeliveryStatus status;
        Instant nextAttemptAt = clock.now();
        Instant completedAt = null;
        String providerMessageId = null;
        if (attempt.outcome() == DeliveryAttempt.Outcome.ACCEPTED) {
            status = DeliveryStatus.SUCCEEDED;
            completedAt = attempt.attemptedAt();
            providerMessageId = sent.providerMessageId();
        } else if (attempt.outcome() == DeliveryAttempt.Outcome.REJECTED || policy.exhausted(attemptsMade)) {
            // A rejected request is terminal at once: retrying only burns quota. A transient one that has used up its
            // budget is terminal too, which is what makes the retry loop finite.
            status = DeliveryStatus.DEAD;
            completedAt = attempt.attemptedAt();
        } else {
            status = DeliveryStatus.PENDING;
            nextAttemptAt = clock.now().plus(policy.retryDelay(attemptsMade, random.getAsDouble()));
        }
        // The CAS matches on the attempt count the claim wrote, so a row another worker has taken over is left alone
        // and its newer owner's result stands. The return value is the round's evidence, not a decision.
        DeliveryStatus decided = status;
        Instant next = nextAttemptAt;
        Instant completed = completedAt;
        String messageId = providerMessageId;
        unitOfWork.transactional(() -> {
            int applied = claims.recordOutcome(
                    item.delivery().id(), attemptsMade, attempt, decided, messageId, next, completed);
            // The CAS says whether this worker still owned the row. Only its owner reports the outcome, so a result
            // event can never describe an outcome another worker overwrote.
            if (applied == 1 && (decided == DeliveryStatus.SUCCEEDED || decided == DeliveryStatus.DEAD)) {
                results.publish(new DeliveryResult(
                        tenantId,
                        item.delivery().id(),
                        item.delivery().requestId(),
                        item.delivery().recipient().address(),
                        decided,
                        attemptsMade,
                        completed,
                        messageId,
                        attempt.error()));
            }
            return applied;
        });
        return status;
    }

    private Sent send(TenantId tenantId, ClaimedDelivery item) {
        EmailDelivery delivery = item.delivery();
        Instant startedAt = clock.now();
        try {
            ResolvedSender resolved = resolver.resolve(tenantId, delivery.fromKey(), item.replyTo());
            OutgoingEmail email = new OutgoingEmail(
                    tenantId, delivery.recipient(), item.cc(), resolved, delivery.subject(), item.html(), item.text(),
                    item.headers());
            SendOutcome outcome = sender.send(email);
            return switch (outcome) {
                case SendOutcome.Accepted accepted -> new Sent(
                        new DeliveryAttempt(delivery.attempts(), DeliveryAttempt.Outcome.ACCEPTED, null, startedAt),
                        accepted.messageId());
                case SendOutcome.Rejected rejected -> new Sent(
                        new DeliveryAttempt(delivery.attempts(), DeliveryAttempt.Outcome.REJECTED, rejected.reason(), startedAt),
                        null);
                case SendOutcome.Transient transientOutcome -> new Sent(
                        new DeliveryAttempt(
                                delivery.attempts(), DeliveryAttempt.Outcome.TRANSIENT, transientOutcome.reason(), startedAt),
                        null);
            };
        } catch (UnresolvableSenderException unresolvable) {
            return new Sent(
                    new DeliveryAttempt(delivery.attempts(), DeliveryAttempt.Outcome.REJECTED, unresolvable.getMessage(), startedAt),
                    null);
        } catch (RuntimeException failure) {
            // An adapter that threw instead of answering is a transport condition: let the budget decide.
            return new Sent(
                    new DeliveryAttempt(
                            delivery.attempts(), DeliveryAttempt.Outcome.TRANSIENT, failure.getClass().getSimpleName(), startedAt),
                    null);
        }
    }

    private record Sent(DeliveryAttempt attempt, String providerMessageId) {}

    /**
     * Publishes the terminal result of every delivery the sweep just killed, inside the sweep's own transaction.
     *
     * <p>A lost worker's final attempt is a real {@code DEAD} outcome; the producer is owed the event exactly as it is
     * for an outcome this worker wrote itself. Publishing here, before the claim, keeps the sweep and its events
     * atomic: a crash rolls both back, and the next round finds the same rows.
     */
    private void publishExhausted(TenantId tenantId, List<ExhaustedDelivery> exhausted) {
        for (ExhaustedDelivery delivery : exhausted) {
            results.publish(new DeliveryResult(
                    tenantId,
                    delivery.deliveryId(),
                    delivery.requestId(),
                    delivery.recipient(),
                    DeliveryStatus.DEAD,
                    delivery.attempts(),
                    delivery.completedAt(),
                    null,
                    delivery.lastError()));
        }
    }
}
