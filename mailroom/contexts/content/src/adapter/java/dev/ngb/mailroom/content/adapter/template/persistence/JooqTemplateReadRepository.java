package dev.ngb.mailroom.content.adapter.template.persistence;

import static dev.ngb.mailroom_jooq.Tables.EMAIL_TEMPLATES;
import static dev.ngb.mailroom_jooq.Tables.EMAIL_TEMPLATE_REVISIONS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.mailroom.content.application.template.query.TemplateListing;
import dev.ngb.mailroom.content.application.template.query.TemplateReadRepository;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionListing;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionView;
import dev.ngb.mailroom.content.application.template.query.TemplateView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Lists templates and their revision history; filter, sort and paging follow the list-query contract. */
public class JooqTemplateReadRepository implements TemplateReadRepository {

    /** The template fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(TemplateListing.SCHEMA)
            .column("id", EMAIL_TEMPLATES.ID)
            .column("key", EMAIL_TEMPLATES.KEY)
            .column("name", EMAIL_TEMPLATES.NAME)
            .column("current_revision", EMAIL_TEMPLATES.CURRENT_REVISION)
            .column("version", EMAIL_TEMPLATES.VERSION)
            .column("created_at", EMAIL_TEMPLATES.CREATED_AT)
            .column("updated_at", EMAIL_TEMPLATES.UPDATED_AT)
            .build();

    /** The revision fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding REVISION_BINDING = ListBinding.of(TemplateRevisionListing.SCHEMA)
            .column("id", EMAIL_TEMPLATE_REVISIONS.ID)
            .column("revision", EMAIL_TEMPLATE_REVISIONS.REVISION)
            .column("subject", EMAIL_TEMPLATE_REVISIONS.SUBJECT)
            .column("created_at", EMAIL_TEMPLATE_REVISIONS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the bindings of the template listings, for the test that checks every sortable field has an index */
    public static List<ListBinding> listBindings() {
        return List.of(BINDING, REVISION_BINDING);
    }

    public JooqTemplateReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<TemplateView> list(ListQuery query) {
        return JooqLists.fetch(dsl, EMAIL_TEMPLATES, noCondition(), BINDING, query, JooqTemplateReadRepository::toView);
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
    public Page<TemplateRevisionView> revisions(UUID templateId, ListQuery query) {
        return JooqLists.fetch(dsl, EMAIL_TEMPLATE_REVISIONS, EMAIL_TEMPLATE_REVISIONS.TEMPLATE_ID.eq(templateId),
                REVISION_BINDING, query, JooqTemplateReadRepository::toView);
    }

    private static TemplateView toView(dev.ngb.mailroom_jooq.tables.records.EmailTemplatesRecord row) {
        return new TemplateView(row.getId(), row.getKey(), row.getName(), row.getCurrentRevision(), row.getVersion());
    }

    private static TemplateRevisionView toView(dev.ngb.mailroom_jooq.tables.records.EmailTemplateRevisionsRecord row) {
        return new TemplateRevisionView(
                row.getId(), row.getRevision(), row.getSubject(), row.getHtml(), row.getText(), row.getCreatedAt());
    }
}
