package dev.ngb.email.content.adapter.template.persistence;

import static dev.ngb.email_jooq.Tables.EMAIL_TEMPLATES;
import static dev.ngb.email_jooq.Tables.EMAIL_TEMPLATE_REVISIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.content.application.template.port.TemplateRepository;
import dev.ngb.email.content.domain.template.Template;
import dev.ngb.email.content.domain.template.TemplateRevision;
import dev.ngb.email.shared.TemplateKey;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** The write side of templates; every statement runs inside the caller's tenant-scoped transaction. */
public class JooqTemplateRepository implements TemplateRepository {

    private final DSLContext dsl;

    public JooqTemplateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Template template, TemplateRevision firstRevision) {
        dsl.insertInto(EMAIL_TEMPLATES)
                .set(EMAIL_TEMPLATES.ID, template.id())
                .set(EMAIL_TEMPLATES.TENANT_ID, template.tenantId().value())
                .set(EMAIL_TEMPLATES.KEY, template.key().value())
                .set(EMAIL_TEMPLATES.NAME, template.name())
                .set(EMAIL_TEMPLATES.CURRENT_REVISION, template.currentRevision())
                .set(EMAIL_TEMPLATES.VERSION, template.version())
                .set(EMAIL_TEMPLATES.CREATED_AT, template.createdAt())
                .set(EMAIL_TEMPLATES.UPDATED_AT, template.updatedAt())
                .execute();
        insertRevision(firstRevision);
    }

    @Override
    public boolean addRevision(Template template, TemplateRevision revision, long expectedVersion) {
        // The revision is inserted first; a failed compare-and-swap rolls the whole transaction back, so no orphan
        // revision survives a lost optimistic-lock race.
        insertRevision(revision);
        return dsl.update(EMAIL_TEMPLATES)
                        .set(EMAIL_TEMPLATES.NAME, template.name())
                        .set(EMAIL_TEMPLATES.CURRENT_REVISION, template.currentRevision())
                        .set(EMAIL_TEMPLATES.VERSION, template.version())
                        .set(EMAIL_TEMPLATES.UPDATED_AT, template.updatedAt())
                        .where(EMAIL_TEMPLATES.ID.eq(template.id()))
                        .and(EMAIL_TEMPLATES.TENANT_ID.eq(template.tenantId().value()))
                        .and(EMAIL_TEMPLATES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean delete(UUID templateId, long expectedVersion) {
        return dsl.deleteFrom(EMAIL_TEMPLATES)
                        .where(EMAIL_TEMPLATES.ID.eq(templateId))
                        .and(EMAIL_TEMPLATES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public Optional<Template> findById(UUID templateId) {
        return dsl.selectFrom(EMAIL_TEMPLATES)
                .where(EMAIL_TEMPLATES.ID.eq(templateId))
                .fetchOptional(JooqTemplateRepository::toTemplate);
    }

    @Override
    public Optional<Template> findByKey(TemplateKey key) {
        return dsl.selectFrom(EMAIL_TEMPLATES)
                .where(EMAIL_TEMPLATES.KEY.eq(key.value()))
                .fetchOptional(JooqTemplateRepository::toTemplate);
    }

    @Override
    public Optional<TemplateRevision> findRevision(UUID templateId, int revision) {
        return dsl.selectFrom(EMAIL_TEMPLATE_REVISIONS)
                .where(EMAIL_TEMPLATE_REVISIONS.TEMPLATE_ID.eq(templateId))
                .and(EMAIL_TEMPLATE_REVISIONS.REVISION.eq(revision))
                .fetchOptional(row -> new TemplateRevision(
                        row.getId(),
                        new TenantId(row.getTenantId()),
                        row.getTemplateId(),
                        row.getRevision(),
                        row.getSubject(),
                        row.getHtml(),
                        row.getText(),
                        row.getCreatedAt()));
    }

    private void insertRevision(TemplateRevision revision) {
        dsl.insertInto(EMAIL_TEMPLATE_REVISIONS)
                .set(EMAIL_TEMPLATE_REVISIONS.ID, revision.id())
                .set(EMAIL_TEMPLATE_REVISIONS.TENANT_ID, revision.tenantId().value())
                .set(EMAIL_TEMPLATE_REVISIONS.TEMPLATE_ID, revision.templateId())
                .set(EMAIL_TEMPLATE_REVISIONS.REVISION, revision.revision())
                .set(EMAIL_TEMPLATE_REVISIONS.SUBJECT, revision.subject())
                .set(EMAIL_TEMPLATE_REVISIONS.HTML, revision.html())
                .set(EMAIL_TEMPLATE_REVISIONS.TEXT, revision.text())
                .set(EMAIL_TEMPLATE_REVISIONS.CREATED_AT, revision.createdAt())
                .execute();
    }

    private static Template toTemplate(
            dev.ngb.email_jooq.tables.records.EmailTemplatesRecord row) {
        return new Template(
                row.getId(),
                new TenantId(row.getTenantId()),
                new TemplateKey(row.getKey()),
                row.getName(),
                row.getCurrentRevision(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
