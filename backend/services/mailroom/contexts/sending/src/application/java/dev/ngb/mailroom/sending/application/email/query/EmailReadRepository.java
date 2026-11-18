package dev.ngb.mailroom.sending.application.email.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of the sent-email ledger. */
public interface EmailReadRepository {

    Page<EmailView> list(ListQuery query);

    Optional<EmailView> find(UUID emailId);
}
