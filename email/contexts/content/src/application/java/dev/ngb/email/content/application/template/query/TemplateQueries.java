package dev.ngb.email.content.application.template.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The template listing and history the API returns, read inside a tenant-scoped transaction. */
public class TemplateQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final TemplateReadRepository templates;

    public TemplateQueries(UnitOfWork unitOfWork, TemplateReadRepository templates) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
    }

    public List<TemplateView> list(Integer limit, UUID after) {
        return unitOfWork.transactional(
                () -> templates.list(after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public TemplateView get(UUID templateId) {
        return unitOfWork.transactional(() -> templates.find(templateId)
                .orElseThrow(() -> new NotFoundException("No template exists here.")));
    }

    public List<TemplateRevisionView> revisions(UUID templateId, int afterRevision, Integer limit) {
        return unitOfWork.transactional(() ->
                templates.revisions(templateId, afterRevision, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }
}
