package dev.ngb.email.content.application.template.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.content.application.template.port.TemplateRepository;
import dev.ngb.email.content.domain.template.RenderLimits;
import dev.ngb.email.content.domain.template.Template;
import dev.ngb.email.content.domain.template.TemplatePolicy;
import dev.ngb.email.content.domain.template.TemplateRevision;
import java.time.Instant;
import java.util.UUID;

/**
 * Appends a new immutable revision and moves the template's pointer, guarded by {@code If-Match}. The edit never
 * rewrites a revision an email or a campaign already recorded.
 */
public class UpdateTemplateUseCase {

    public record Change(String name, String subject, String html, String text) {}

    private final UnitOfWork unitOfWork;
    private final TemplateRepository templates;
    private final IdGenerator ids;
    private final Clock clock;
    private final RenderLimits limits;

    public UpdateTemplateUseCase(
            UnitOfWork unitOfWork,
            TemplateRepository templates,
            IdGenerator ids,
            Clock clock,
            RenderLimits limits) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
        this.ids = ids;
        this.clock = clock;
        this.limits = limits;
    }

    public Template execute(UUID templateId, Change change, ExpectedVersion expected) {
        TemplatePolicy.validateSubmission(change.subject(), change.html(), change.text(), limits);
        return unitOfWork.transactional(() -> {
            Template current = templates.findById(templateId)
                    .orElseThrow(() -> new NotFoundException("No template exists here."));
            expected.check(current.version());
            Instant now = clock.now();
            int next = current.currentRevision() + 1;
            Template updated = current.withRevision(next, change.name(), now);
            TemplateRevision revision = new TemplateRevision(
                    ids.newId(), current.tenantId(), templateId, next, change.subject(), change.html(), change.text(), now);
            if (!templates.addRevision(updated, revision, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
