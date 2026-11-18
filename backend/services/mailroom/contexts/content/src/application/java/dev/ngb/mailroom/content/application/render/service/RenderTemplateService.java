package dev.ngb.mailroom.content.application.render.service;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.content.api.render.RenderTemplate;
import dev.ngb.mailroom.content.api.render.RenderedEmail;
import dev.ngb.mailroom.content.api.render.TemplateSummary;
import dev.ngb.mailroom.content.application.render.port.RenderedParts;
import dev.ngb.mailroom.content.application.render.port.TemplateRenderer;
import dev.ngb.mailroom.content.application.template.port.TemplateRepository;
import dev.ngb.mailroom.content.domain.template.Template;
import dev.ngb.mailroom.content.domain.template.TemplateRevision;
import dev.ngb.mailroom.shared.TemplateKey;
import java.util.Map;
import java.util.UUID;

/**
 * Answers the sending context by resolving a template and rendering a pinned or current revision. It opens a unit of
 * work so it also reads correctly when called outside a caller's transaction; a call from inside one joins it.
 */
public class RenderTemplateService implements RenderTemplate {

    private final UnitOfWork unitOfWork;
    private final TemplateRepository templates;
    private final TemplateRenderer renderer;

    public RenderTemplateService(
            UnitOfWork unitOfWork, TemplateRepository templates, TemplateRenderer renderer) {
        this.unitOfWork = unitOfWork;
        this.templates = templates;
        this.renderer = renderer;
    }

    @Override
    public TemplateSummary resolve(TemplateKey key) {
        return unitOfWork.transactional(() -> {
            Template template = templateByKey(key);
            return new TemplateSummary(template.id(), template.currentRevision());
        });
    }

    @Override
    public RenderedEmail render(TemplateKey key, Map<String, Object> variables) {
        return unitOfWork.transactional(() -> {
            Template template = templateByKey(key);
            return renderPinned(template.id(), template.currentRevision(), variables);
        });
    }

    @Override
    public RenderedEmail renderPinned(UUID templateId, int revision, Map<String, Object> variables) {
        return unitOfWork.transactional(() -> {
            TemplateRevision pinned = templates.findRevision(templateId, revision)
                    .orElseThrow(() -> new NotFoundException("The template revision this email needs no longer exists."));
            RenderedParts parts = renderer.render(pinned.subject(), pinned.html(), pinned.text(), variables);
            return new RenderedEmail(templateId, revision, parts.subject(), parts.html(), parts.text());
        });
    }

    @Override
    public RenderedEmail renderInline(String subject, String html, String text, Map<String, Object> variables) {
        RenderedParts parts = renderer.render(subject, html, text, variables);
        return new RenderedEmail(null, 0, parts.subject(), parts.html(), parts.text());
    }

    private Template templateByKey(TemplateKey key) {
        return templates.findByKey(key)
                .orElseThrow(() -> new NotFoundException("No template exists for that key."));
    }
}
