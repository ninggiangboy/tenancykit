package dev.ngb.email.content.application.template.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.email.content.application.render.port.RenderedParts;
import dev.ngb.email.content.application.render.port.TemplateRenderer;
import dev.ngb.email.content.application.template.port.TemplateRepository;
import dev.ngb.email.content.domain.template.Template;
import dev.ngb.email.content.domain.template.TemplateRevision;
import java.util.Map;
import java.util.UUID;

/** Renders the current revision with caller-supplied variables, under exactly the limits a real send obeys. */
public class PreviewTemplateUseCase {

    private final UnitOfWork unitOfWork;
    private final TemplateRepository templates;
    private final TemplateRenderer renderer;

    public PreviewTemplateUseCase(
            UnitOfWork unitOfWork, TemplateRepository templates, TemplateRenderer renderer) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
        this.renderer = renderer;
    }

    public RenderedParts execute(UUID templateId, Map<String, Object> variables) {
        return unitOfWork.transactional(() -> {
            Template template = templates.findById(templateId)
                    .orElseThrow(() -> new NotFoundException("No template exists here."));
            TemplateRevision revision = templates.findRevision(templateId, template.currentRevision())
                    .orElseThrow(() -> new NotFoundException("This template has no current revision."));
            return renderer.render(revision.subject(), revision.html(), revision.text(), variables);
        });
    }
}
