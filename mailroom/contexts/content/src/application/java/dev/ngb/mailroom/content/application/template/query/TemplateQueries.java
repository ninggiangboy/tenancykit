package dev.ngb.mailroom.content.application.template.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The template listing and history the API returns, read inside a tenant-scoped transaction. */
public class TemplateQueries {

    private final UnitOfWork unitOfWork;
    private final TemplateReadRepository templates;

    public TemplateQueries(UnitOfWork unitOfWork, TemplateReadRepository templates) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
    }

    public Page<TemplateView> list(ListQuery query) {
        return unitOfWork.transactional(() -> templates.list(query));
    }

    public TemplateView get(UUID templateId) {
        return unitOfWork.transactional(() -> templates.find(templateId)
                .orElseThrow(() -> new NotFoundException("No template exists here.")));
    }

    public Page<TemplateRevisionView> revisions(UUID templateId, ListQuery query) {
        return unitOfWork.transactional(() -> templates.revisions(templateId, query));
    }
}
