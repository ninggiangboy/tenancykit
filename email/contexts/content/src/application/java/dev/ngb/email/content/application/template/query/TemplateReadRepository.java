package dev.ngb.email.content.application.template.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's templates; row-level security restricts every query to the bound tenant. */
public interface TemplateReadRepository {

    List<TemplateView> list(UUID afterId, int limit);

    Optional<TemplateView> find(UUID templateId);

    List<TemplateRevisionView> revisions(UUID templateId, int afterRevision, int limit);
}
