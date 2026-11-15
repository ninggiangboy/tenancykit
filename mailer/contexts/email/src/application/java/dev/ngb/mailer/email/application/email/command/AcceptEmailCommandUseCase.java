package dev.ngb.mailer.email.application.email.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailer.email.application.email.port.EmailDeliveryRepository;
import dev.ngb.mailer.email.application.email.port.MailerTenants;
import dev.ngb.mailer.email.domain.delivery.EmailDelivery;
import dev.ngb.mailer.shared.FromKey;
import dev.ngb.mailer.shared.Recipient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one validated {@code email.send} command into ledger rows. It writes and returns: it never calls a provider,
 * so a retried command can never duplicate an email, and the caller's transaction stays short.
 *
 * <p>A repeated request is not automatically a no-op. The inbox is keyed by event id and the ledger by
 * {@code (tenant, requestId, recipient)}, so a producer that times out and retries sends a <em>new</em> event id with
 * the <em>same</em> request id: the inbox says "first time", the ledger insert conflicts, and a plain
 * insert-on-conflict would drop the command in silence. The policy is therefore status-aware:
 * <ul>
 *   <li>every matching row live — no-op, because the email is going out or already has;</li>
 *   <li>a matching row {@code DEAD} — resurrect it, because the plan's promise is that a dead delivery can be
 *       requested again;</li>
 *   <li>mixed — leave the live rows alone and resurrect only the dead ones.</li>
 * </ul>
 */
public class AcceptEmailCommandUseCase {

    /** The decoded, schema-validated payload. The envelope's tenant is supplied separately, because it is the scope, not the content. */
    public record Command(
            String requestId,
            List<String> to,
            List<String> cc,
            String fromKey,
            String fromName,
            String replyTo,
            String subject,
            String html,
            String text,
            Map<String, String> headers) {

        public Command {
            to = List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** What one command did, which is a log line and a metric — the handler returns nothing. */
    public enum Outcome {
        /** At least one ledger row was written. */
        CREATED,
        /** Every recipient already had a live or successful delivery; nothing was sent twice. */
        DUPLICATE,
        /** At least one dead delivery was brought back, so the producer's retry actually changed something. */
        RESURRECTED
    }

    private final UnitOfWork unitOfWork;
    private final EmailDeliveryRepository deliveries;
    private final MailerTenants tenants;
    private final Clock clock;
    private final IdGenerator ids;

    public AcceptEmailCommandUseCase(
            UnitOfWork unitOfWork,
            EmailDeliveryRepository deliveries,
            MailerTenants tenants,
            Clock clock,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
        this.tenants = tenants;
        this.clock = clock;
        this.ids = ids;
    }

    public Outcome accept(TenantId tenantId, Command command) {
        return unitOfWork.transactional(() -> {
            // The delivery sweep visits only tenants registered here, and before this call the table was written by a
            // migration alone: a command for an ordinary tenant was accepted and then sat PENDING for ever. Registering
            // in the accept transaction makes "accepted" mean "the sweep will see it", and the insert is idempotent.
            tenants.register(tenantId);
            Instant now = clock.now();
            List<Recipient> recipients = command.to().stream().map(Recipient::of).toList();
            Map<String, EmailDelivery> existing = new LinkedHashMap<>();
            for (EmailDelivery row : deliveries.findExisting(tenantId, command.requestId(), command.to())) {
                existing.put(row.recipient().address(), row);
            }
            FromKey fromKey = FromKey.of(command.fromKey());
            List<Recipient> cc = command.cc().stream().map(Recipient::of).toList();
            boolean created = false;
            boolean resurrected = false;
            for (Recipient recipient : recipients) {
                EmailDelivery row = existing.get(recipient.address());
                if (row == null) {
                    EmailDelivery pending = EmailDelivery.pending(
                            ids.newId(), tenantId, command.requestId(), recipient, fromKey, command.subject(), now);
                    created |= deliveries.addIfAbsent(
                            pending, command.replyTo(), cc, command.html(), command.text(), command.headers());
                } else if (row.isDead()) {
                    deliveries.resurrect(row.id());
                    resurrected = true;
                }
            }
            if (created) {
                return Outcome.CREATED;
            }
            return resurrected ? Outcome.RESURRECTED : Outcome.DUPLICATE;
        });
    }
}
