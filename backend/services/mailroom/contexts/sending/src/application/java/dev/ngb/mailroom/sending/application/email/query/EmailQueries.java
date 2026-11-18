package dev.ngb.mailroom.sending.application.email.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The email listing and detail endpoints, read inside a tenant-scoped transaction. */
public class EmailQueries {

    private final UnitOfWork unitOfWork;
    private final EmailReadRepository emails;

    public EmailQueries(UnitOfWork unitOfWork, EmailReadRepository emails) {
        this.unitOfWork = unitOfWork;
        this.emails = emails;
    }

    public Page<EmailView> list(ListQuery query) {
        return unitOfWork.transactional(() -> emails.list(query));
    }

    public EmailView get(UUID emailId) {
        return unitOfWork.transactional(
                () -> emails.find(emailId).orElseThrow(() -> new NotFoundException("No email exists here.")));
    }
}
