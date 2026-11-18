package dev.ngb.workflow.triggers.application.trigger.command;

import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.api.instance.StartRequest;
import dev.ngb.workflow.runtime.api.wait.WaitResolver;
import dev.ngb.workflow.triggers.application.trigger.port.InboundEventRepository;
import dev.ngb.workflow.triggers.application.trigger.port.SecretCipher;
import dev.ngb.workflow.triggers.application.trigger.port.TriggerRepository;
import dev.ngb.workflow.triggers.application.trigger.service.EventEnvelope;
import dev.ngb.workflow.triggers.application.trigger.service.EventFilters;
import dev.ngb.workflow.triggers.application.trigger.service.WebhookSignatures;
import dev.ngb.workflow.triggers.domain.event.InboundEvent;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receives one signed hook: verify it with the named trigger's secret, keep the event once, then start every matching
 * trigger in the same transaction.
 *
 * <p>A duplicate event id is answered as accepted and starts nothing: the producer's redelivery must not run the flow
 * twice. An unknown trigger, a tampered body, a stale timestamp and a wrong secret all answer the same refusal.
 */
public class ReceiveEventUseCase {

    /** How long an event is kept for a wait that registers after it arrived. */
    public static final Duration BUFFER = Duration.ofDays(7);

    public record Command(UUID triggerId, Map<String, String> headers, String rawBody, Object document) {}

    /** What the delivery did: the event id, how many instances started, and whether it was already known. */
    public record Receipt(UUID eventId, int started, boolean duplicate) {}

    private final UnitOfWork unitOfWork;
    private final TriggerRepository triggers;
    private final InboundEventRepository events;
    private final VersionReader versions;
    private final InstanceStarter starter;
    private final WaitResolver waits;
    private final SecretCipher cipher;
    private final EventFilters filters;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ReceiveEventUseCase(
            UnitOfWork unitOfWork,
            TriggerRepository triggers,
            InboundEventRepository events,
            VersionReader versions,
            InstanceStarter starter,
            WaitResolver waits,
            SecretCipher cipher,
            EventFilters filters,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.triggers = triggers;
        this.events = events;
        this.versions = versions;
        this.starter = starter;
        this.waits = waits;
        this.cipher = cipher;
        this.filters = filters;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Receipt execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            Trigger hook = triggers
                    .findById(command.triggerId())
                    .orElseThrow(() -> new UnauthorizedException("The delivery could not be verified."));
            if (!hook.kind().signed() || hook.secretCiphertext() == null) {
                throw new UnauthorizedException("The delivery could not be verified.");
            }
            String source = hook.source();
            String secret = cipher.decrypt(hook.secretCiphertext());
            WebhookSignatures.Delivery delivery =
                    WebhookSignatures.verified(source, command.headers(), command.rawBody(), secret, clock.now());
            EventEnvelope event = EventEnvelope.of(delivery, command.document());

            Instant now = clock.now();
            boolean fresh = events.insertIfAbsent(new InboundEvent(
                    ids.newId(), tenant.value(), source, event.eventId(), event.type(), command.document(),
                    now, now.plus(BUFFER)));
            if (!fresh) {
                return new Receipt(event.eventId(), 0, true);
            }
            // Waits parked in this tenant resume in the same transaction as the event that satisfies them.
            waits.resolve(source, event.type(), command.document(), now);

            int started = 0;
            List<Trigger> candidates = triggers.enabledForSource(source, event.type());
            for (Trigger candidate : candidates) {
                if (!filters.matches(candidate, event)) {
                    continue;
                }
                PublishedGraph version = versions.latest(candidate.definitionId()).orElse(null);
                if (version == null) {
                    continue;
                }
                starter.start(new StartRequest(
                        version.versionId(),
                        filters.input(candidate, event),
                        filters.correlation(candidate, event),
                        "trigger/" + candidate.id(),
                        null,
                        null));
                started++;
            }
            return new Receipt(event.eventId(), started, false);
        });
    }
}
