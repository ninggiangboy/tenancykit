package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.shared.Recipient;
import java.util.List;
import java.util.Objects;

/**
 * One email ready to hand to a transport: the message plus the identity it is sent as. The sender is already resolved
 * and the reply-to already checked, so an adapter never reads configuration and never decides where a message comes
 * from. Carrying the resolution here is what lets the SES adapter reach its region and credentials without the port
 * naming an SDK type.
 */
public record OutgoingEmail(
        TenantId tenantId,
        Recipient recipient,
        List<Recipient> cc,
        ResolvedSender sender,
        String subject,
        String html,
        String text) {

    public OutgoingEmail {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(subject, "subject");
        cc = cc == null ? List.of() : List.copyOf(cc);
        if (html == null && text == null) {
            throw new IllegalArgumentException("an email carries an html part, a text part, or both");
        }
    }
}
