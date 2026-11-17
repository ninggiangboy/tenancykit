package dev.ngb.workflow.triggers.application.trigger.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.triggers.application.trigger.port.CronCalendar;
import dev.ngb.workflow.triggers.application.trigger.port.SecretCipher;
import dev.ngb.workflow.triggers.application.trigger.port.ScheduleRepository;
import dev.ngb.workflow.triggers.application.trigger.port.TriggerRepository;
import dev.ngb.workflow.triggers.application.trigger.service.TriggerRules;
import dev.ngb.workflow.triggers.domain.schedule.Schedule;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import dev.ngb.workflow.triggers.domain.trigger.TriggerKind;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Creates, edits and disables a trigger.
 *
 * <p>A signed trigger is given a secret once, at creation, and the response carries it in the clear the only time it
 * ever can; the stored value is an encrypted envelope. A schedule trigger writes its schedule row with the first tick
 * after now, so a schedule created while the worker is down does not fire every missed tick at once.
 */
public class ManageTriggerUseCase {

    public record CreateCommand(
            String name,
            String kind,
            String source,
            String eventType,
            UUID definitionId,
            Object filter,
            Object correlation,
            Object input,
            String cron,
            String timezone) {}

    public record UpdateCommand(
            UUID triggerId,
            ExpectedVersion expected,
            String name,
            String eventType,
            Object filter,
            Object correlation,
            Object input,
            Boolean enabled) {}

    /** The trigger, and the secret the one response may show. */
    public record Created(Trigger trigger, String secret) {}

    private final UnitOfWork unitOfWork;
    private final TriggerRepository triggers;
    private final ScheduleRepository schedules;
    private final VersionReader versions;
    private final SecretCipher cipher;
    private final CronCalendar calendar;
    private final TriggerRules rules;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ManageTriggerUseCase(
            UnitOfWork unitOfWork,
            TriggerRepository triggers,
            ScheduleRepository schedules,
            VersionReader versions,
            SecretCipher cipher,
            CronCalendar calendar,
            TriggerRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.triggers = triggers;
        this.schedules = schedules;
        this.versions = versions;
        this.cipher = cipher;
        this.calendar = calendar;
        this.rules = rules;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Created create(CreateCommand command) {
        TriggerKind kind = rules.kind(command.kind());
        String name = rules.name(command.name());
        String source = rules.source(kind, command.source());
        String eventType = rules.eventType(command.eventType());
        if (kind == TriggerKind.SCHEDULE) {
            rules.schedule(command.cron(), command.timezone(), calendar);
        }
        String secret = kind.signed() ? newSecret() : null;
        TenantId tenant = tenantContext.requireTenant();
        String secretCiphertext = secret == null ? null : cipher.encrypt(secret);

        Trigger trigger = unitOfWork.transactional(() -> {
            if (versions.latest(command.definitionId()).isEmpty()) {
                throw new NotFoundException("No published version of this workflow exists here.");
            }
            Instant now = clock.now();
            Trigger created = new Trigger(
                    ids.newId(),
                    tenant.value(),
                    name,
                    kind,
                    source,
                    eventType,
                    command.definitionId(),
                    command.filter(),
                    command.correlation(),
                    command.input(),
                    secretCiphertext,
                    secret == null ? null : cipher.keyId(),
                    true,
                    now,
                    now,
                    1);
            triggers.add(created);
            if (kind == TriggerKind.SCHEDULE) {
                schedules.add(new Schedule(
                        ids.newId(),
                        tenant.value(),
                        created.id(),
                        command.cron().strip(),
                        command.timezone() == null || command.timezone().isBlank() ? "UTC" : command.timezone().strip(),
                        calendar.next(command.cron(), command.timezone(), now),
                        null,
                        1));
            }
            return created;
        });
        return new Created(trigger, secret);
    }

    public Trigger update(UpdateCommand command) {
        String name = command.name() == null ? null : rules.name(command.name());
        return unitOfWork.transactional(() -> {
            Trigger trigger = triggers
                    .lockById(command.triggerId())
                    .orElseThrow(() -> new NotFoundException("No workflow trigger exists here."));
            command.expected().check(trigger.version());
            Trigger updated = trigger.updated(
                    name == null ? trigger.name() : name,
                    command.eventType() == null ? trigger.eventType() : rules.eventType(command.eventType()),
                    command.filter() == null ? trigger.filter() : command.filter(),
                    command.correlation() == null ? trigger.correlation() : command.correlation(),
                    command.input() == null ? trigger.input() : command.input(),
                    command.enabled() == null ? trigger.enabled() : command.enabled(),
                    clock.now());
            triggers.update(updated);
            return updated;
        });
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
