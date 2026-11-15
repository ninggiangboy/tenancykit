package dev.ngb.email.sending.application.email.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.util.Text;
import dev.ngb.email.audience.api.eligibility.SendEligibility;
import dev.ngb.email.content.api.render.MissingTemplateVariableException;
import dev.ngb.email.content.api.render.RenderTemplate;
import dev.ngb.email.content.api.render.RenderedEmail;
import dev.ngb.email.content.api.render.TemplateLimitExceededException;
import dev.ngb.email.sending.application.email.port.EmailCommandFactory;
import dev.ngb.email.sending.application.email.port.EmailRepository;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.email.port.TrackingHook;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.shared.EmailKind;
import dev.ngb.email.shared.TemplateKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders one email for one recipient, records it and hands the {@code email.send} command to the outbox.
 *
 * <p>Eligibility and rendering are decided here, before the row is written: a suppressed address is stored
 * {@code SKIPPED}, and a failed render is stored {@code FAILED} with the reason. Neither writes an outbox row, so
 * nothing is sent. The insert and the outbox append share one transaction, so the email and its command either both
 * exist or neither does.
 *
 * <p>{@code requestId} is mailer's idempotency key. A caller-supplied {@code Idempotency-Key} becomes its namespace,
 * so a retried call returns the same email. A campaign passes one deterministic {@code emailId} per recipient, so a
 * worker that crashed after the outbox write republishes nothing on its next tick.
 */
public class SendEmailUseCase {

    public static final String EMAIL_SEND = "email.send";
    private static final int MAX_REQUEST_ID = 200;
    private static final int MAX_REASON = 500;

    /**
     * @param emailId a caller-chosen identity, or null to generate one
     * @param templateId a pinned template revision for a campaign, or null to use the key's current revision
     * @param idempotencyKey the caller's {@code Idempotency-Key}, or null
     */
    public record Command(
            String to,
            EmailKind kind,
            String fromKey,
            String templateKey,
            Map<String, Object> variables,
            String subject,
            String html,
            String text,
            String idempotencyKey,
            String fromName,
            String replyTo,
            UUID emailId,
            UUID campaignId,
            UUID receiverId,
            UUID templateId,
            Integer templateRevision) {

        /** A plain transactional send: no campaign, no pinned revision, a generated identity. */
        public static Command of(
                String to,
                EmailKind kind,
                String fromKey,
                String templateKey,
                Map<String, Object> variables,
                String subject,
                String html,
                String text,
                String idempotencyKey) {
            return new Command(
                    to, kind, fromKey, templateKey, variables, subject, html, text, idempotencyKey,
                    null, null, null, null, null, null, null);
        }
    }

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final EmailRepository emails;
    private final EmailSettingsRepository settings;
    private final RenderTemplate renderer;
    private final SendEligibility eligibility;
    private final EmailCommandFactory commands;
    private final EventPublisher publisher;
    private final TrackingHook tracking;
    private final IdGenerator ids;
    private final Clock clock;

    public SendEmailUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            EmailRepository emails,
            EmailSettingsRepository settings,
            RenderTemplate renderer,
            SendEligibility eligibility,
            EmailCommandFactory commands,
            EventPublisher publisher,
            TrackingHook tracking,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.emails = emails;
        this.settings = settings;
        this.renderer = renderer;
        this.eligibility = eligibility;
        this.commands = commands;
        this.publisher = publisher;
        this.tracking = tracking;
        this.ids = ids;
        this.clock = clock;
    }

    public Email execute(Command command) {
        Objects.requireNonNull(command, "command");
        TenantId tenantId = tenantContext.requireTenant();
        Map<String, Object> variables = command.variables() == null ? Map.of() : command.variables();
        UUID emailId = command.emailId() == null ? ids.newId() : command.emailId();
        String requestId = requestId(command.idempotencyKey(), emailId);
        Map<String, Object> renderVariables = new LinkedHashMap<>(variables);
        renderVariables.putAll(tracking.systemVariables(command.kind(), emailId, tenantId));

        return unitOfWork.transactional(() -> {
            // The settings row and the eligibility check are read inside the transaction: row-level security needs
            // the tenant set on the connection, which only a unit of work does.
            EmailSettingsView effective = settings.current();
            String fromKey = blank(command.fromKey()) ? effective.fromKey() : command.fromKey().trim();
            String fromName = command.fromName() == null ? effective.fromName() : command.fromName();
            String replyTo = command.replyTo() == null ? effective.replyTo() : command.replyTo();
            var existing = emails.findByRequestId(requestId);
            if (existing.isPresent()) {
                return existing.get();
            }
            Instant now = clock.now();
            Email base = Email.queued(
                    emailId, tenantId, command.kind(), command.campaignId(), command.receiverId(),
                    command.to(), fromKey, fromName, replyTo,
                    safeSubject(command.subject()), null, null, requestId, null, null, now);

            if (!eligibility.allows(tenantId, command.to(), command.kind())) {
                Email skipped = base.skipped("the address is suppressed for this kind of email", now);
                emails.insert(skipped);
                return skipped;
            }

            RenderedEmail rendered;
            try {
                rendered = render(command, renderVariables);
            } catch (RuntimeException refusal) {
                Email failed = base.failed(reason(refusal), now);
                emails.insert(failed);
                return failed;
            }

            TrackingHook.Tracked tracked;
            try {
                tracked = tracking.prepare(rendered, command.kind(), emailId, tenantId);
            } catch (RuntimeException refusal) {
                Email failed = base.failed(reason(refusal), now);
                emails.insert(failed);
                return failed;
            }

            Email queued = Email.queued(
                    emailId, tenantId, command.kind(), command.campaignId(), command.receiverId(),
                    command.to(), fromKey, fromName, replyTo,
                    rendered.subject(), rendered.templateId(),
                    rendered.revision() == 0 ? null : rendered.revision(),
                    requestId, tracked.html(), rendered.text(), now);

            EmailCommandFactory.EmailCommand outbox;
            try {
                outbox = commands.create(queued, tracked.headers());
            } catch (RuntimeException refusal) {
                Email failed = queued.failed(reason(refusal), now);
                emails.insert(failed);
                return failed;
            }

            emails.insert(queued);
            tracked.links().forEach(link -> tracking.recordLink(link, emailId, tenantId, now));
            publisher.publish(new EmailSendCommand(
                    ids.newId(), tenantId, EMAIL_SEND, outbox.aggregate(), now, outbox.payload()));
            return queued;
        });
    }

    private RenderedEmail render(Command command, Map<String, Object> variables) {
        if (command.templateId() != null) {
            return renderer.renderPinned(
                    command.templateId(),
                    command.templateRevision() == null ? 1 : command.templateRevision(),
                    variables);
        }
        if (!blank(command.templateKey())) {
            return renderer.render(new TemplateKey(command.templateKey()), variables);
        }
        if (blank(command.subject()) || (command.html() == null && command.text() == null)) {
            throw new BadRequestException(
                    "an email needs a template key, or a subject with an html or text body");
        }
        return renderer.renderInline(command.subject(), command.html(), command.text(), variables);
    }

    private static String requestId(String idempotencyKey, UUID emailId) {
        if (blank(idempotencyKey)) {
            return emailId.toString();
        }
        String key = "email/" + idempotencyKey.trim();
        if (key.length() > MAX_REQUEST_ID) {
            // Truncating would make two different keys collide on one ledger row and silently turn the second send
            // into a no-op. Refusing is the honest answer; the contract bounds the key so a conforming client never
            // reaches this.
            throw new BadRequestException(
                    "an idempotency key is at most " + (MAX_REQUEST_ID - "email/".length()) + " characters");
        }
        return key;
    }

    private static String safeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "Unsent email";
        }
        String cleaned = subject.replace('\r', ' ').replace('\n', ' ').trim();
        return Text.clip(cleaned, 998);
    }

    /** A reason safe to store: it names a missing variable or a limit, never a body, an address or a token. */
    private static String reason(RuntimeException failure) {
        if (failure instanceof MissingTemplateVariableException
                || failure instanceof TemplateLimitExceededException
                || failure instanceof BadRequestException) {
            return Text.clip(failure.getMessage(), MAX_REASON);
        }
        return "the email could not be prepared";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record EmailSendCommand(
            UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt,
            Map<String, Object> payload)
            implements DomainEvent {}
}
