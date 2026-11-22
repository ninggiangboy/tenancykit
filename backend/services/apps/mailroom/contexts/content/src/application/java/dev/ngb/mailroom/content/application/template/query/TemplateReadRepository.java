package dev.ngb.mailroom.content.application.template.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's templates; row-level security restricts every query to the bound tenant. */
public interface TemplateReadRepository {

    Page<TemplateView> list(ListQuery query);

    Optional<TemplateView> find(UUID templateId);

    Page<TemplateRevisionView> revisions(UUID templateId, ListQuery query);
}
