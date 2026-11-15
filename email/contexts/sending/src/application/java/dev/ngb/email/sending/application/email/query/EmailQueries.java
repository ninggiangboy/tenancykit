package dev.ngb.email.sending.application.email.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The email listing and detail endpoints, read inside a tenant-scoped transaction. */
public class EmailQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final EmailReadRepository emails;

    public EmailQueries(UnitOfWork unitOfWork, EmailReadRepository emails) {
        this.unitOfWork = unitOfWork;
        this.emails = emails;
    }

    public List<EmailView> list(
            String status, String kind, UUID campaignId, String recipient, UUID after, Integer limit) {
        return unitOfWork.transactional(() -> emails.list(
                status, kind, campaignId, recipient, after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public EmailView get(UUID emailId) {
        return unitOfWork.transactional(
                () -> emails.find(emailId).orElseThrow(() -> new NotFoundException("No email exists here.")));
    }
}
