package dev.ngb.mailroom.sending.application.email.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of the sent-email ledger. */
public interface EmailReadRepository {

    List<EmailView> list(
            String status, String kind, UUID campaignId, String recipient, UUID afterId, int limit);

    Optional<EmailView> find(UUID emailId);
}
