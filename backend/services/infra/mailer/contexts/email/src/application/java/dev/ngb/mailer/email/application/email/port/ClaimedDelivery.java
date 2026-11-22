package dev.ngb.mailer.email.application.email.port;

import dev.ngb.mailer.email.domain.delivery.EmailDelivery;
import dev.ngb.mailer.shared.Recipient;
import java.util.List;
import java.util.Map;

/**
 * A delivery a worker now holds, with the body it has to send. The lease end is already committed, so a crash between
 * the claim and the outcome leaves a row that becomes claimable again instead of one that is stuck forever.
 *
 * <p>The body rides along with the claim rather than taking a second read: a body that was purged between the claim and
 * the send would otherwise turn a retry into a crash.
 */
public record ClaimedDelivery(
        EmailDelivery delivery,
        String replyTo,
        List<Recipient> cc,
        String html,
        String text,
        Map<String, String> headers) {

    public ClaimedDelivery {
        cc = cc == null ? List.of() : List.copyOf(cc);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
