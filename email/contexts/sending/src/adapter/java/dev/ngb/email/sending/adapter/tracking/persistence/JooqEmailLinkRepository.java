package dev.ngb.email.sending.adapter.tracking.persistence;

import static dev.ngb.email_jooq.Tables.EMAILS;
import static dev.ngb.email_jooq.Tables.EMAIL_LINKS;

import dev.ngb.email.sending.application.tracking.port.EmailLinkRepository;
import dev.ngb.email.sending.domain.tracking.EmailLink;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The recorded click targets. */
public class JooqEmailLinkRepository implements EmailLinkRepository {

    private final DSLContext dsl;

    public JooqEmailLinkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(EmailLink link) {
        dsl.insertInto(EMAIL_LINKS)
                .set(EMAIL_LINKS.ID, link.id())
                .set(EMAIL_LINKS.TENANT_ID, link.tenantId().value())
                .set(EMAIL_LINKS.EMAIL_ID, link.emailId())
                .set(EMAIL_LINKS.POSITION, link.position())
                .set(EMAIL_LINKS.URL, link.url())
                .set(EMAIL_LINKS.CREATED_AT, link.createdAt())
                .onConflict(EMAIL_LINKS.EMAIL_ID, EMAIL_LINKS.POSITION)
                .doNothing()
                .execute();
    }

    @Override
    public Optional<String> findUrl(UUID emailId, int position) {
        return dsl.select(EMAIL_LINKS.URL)
                .from(EMAIL_LINKS)
                .where(EMAIL_LINKS.EMAIL_ID.eq(emailId))
                .and(EMAIL_LINKS.POSITION.eq(position))
                .fetchOptional(EMAIL_LINKS.URL);
    }

    @Override
    public int purgeForPurgedBodies(Instant bodyCutoff, int limit) {
        var purged = DSL.select(EMAILS.ID)
                .from(EMAILS)
                .where(EMAILS.BODY_PURGED_AT.isNotNull())
                .and(EMAILS.CREATED_AT.lt(bodyCutoff))
                .limit(limit);
        return dsl.deleteFrom(EMAIL_LINKS).where(EMAIL_LINKS.EMAIL_ID.in(purged)).execute();
    }
}
