package dev.ngb.mailroom.content.adapter.template.persistence;

import static dev.ngb.mailroom_jooq.Tables.EMAIL_TEMPLATES;
import static dev.ngb.mailroom_jooq.Tables.EMAIL_TEMPLATE_REVISIONS;

import dev.ngb.mailroom.content.application.template.query.TemplateReadRepository;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionView;
import dev.ngb.mailroom.content.application.template.query.TemplateView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Lists templates and their revision history, newest first; the UUID v7 id is the cursor and the sort key at once. */
public class JooqTemplateReadRepository implements TemplateReadRepository {

    private final DSLContext dsl;

    public JooqTemplateReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TemplateView> list(UUID afterId, int limit) {
        Condition where = EMAIL_TEMPLATES.TENANT_ID.isNotNull();
        if (afterId != null) {
            where = where.and(EMAIL_TEMPLATES.ID.lt(afterId));
        }
        return dsl.select(
                        EMAIL_TEMPLATES.ID,
                        EMAIL_TEMPLATES.KEY,
                        EMAIL_TEMPLATES.NAME,
                        EMAIL_TEMPLATES.CURRENT_REVISION,
                        EMAIL_TEMPLATES.VERSION)
                .from(EMAIL_TEMPLATES)
                .where(where)
                .orderBy(EMAIL_TEMPLATES.ID.desc())
                .limit(limit)
                .fetch(row -> new TemplateView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    @Override
    public Optional<TemplateView> find(UUID templateId) {
        return dsl.select(
                        EMAIL_TEMPLATES.ID,
                        EMAIL_TEMPLATES.KEY,
                        EMAIL_TEMPLATES.NAME,
                        EMAIL_TEMPLATES.CURRENT_REVISION,
                        EMAIL_TEMPLATES.VERSION)
                .from(EMAIL_TEMPLATES)
                .where(EMAIL_TEMPLATES.ID.eq(templateId))
                .fetchOptional(row -> new TemplateView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    @Override
    public List<TemplateRevisionView> revisions(UUID templateId, int afterRevision, int limit) {
        return dsl.select(
                        EMAIL_TEMPLATE_REVISIONS.ID,
                        EMAIL_TEMPLATE_REVISIONS.REVISION,
                        EMAIL_TEMPLATE_REVISIONS.SUBJECT,
                        EMAIL_TEMPLATE_REVISIONS.HTML,
                        EMAIL_TEMPLATE_REVISIONS.TEXT,
                        EMAIL_TEMPLATE_REVISIONS.CREATED_AT)
                .from(EMAIL_TEMPLATE_REVISIONS)
                .where(EMAIL_TEMPLATE_REVISIONS.TEMPLATE_ID.eq(templateId))
                .and(EMAIL_TEMPLATE_REVISIONS.REVISION.gt(afterRevision))
                .orderBy(EMAIL_TEMPLATE_REVISIONS.REVISION.desc())
                .limit(limit)
                .fetch(row -> new TemplateRevisionView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), row.value6()));
    }
}
