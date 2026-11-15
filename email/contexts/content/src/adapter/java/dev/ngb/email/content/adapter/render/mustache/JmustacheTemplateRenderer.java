package dev.ngb.email.content.adapter.render.mustache;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import dev.ngb.email.content.api.render.TemplateLimitExceededException;
import dev.ngb.email.content.application.render.port.RenderedParts;
import dev.ngb.email.content.application.render.port.TemplateRenderer;
import dev.ngb.email.content.domain.template.RenderLimits;
import dev.ngb.email.content.domain.template.TemplatePolicy;
import java.util.Map;

/**
 * JMustache inside a sandbox.
 *
 * <p>The policy is applied again at load time, so a row inserted directly with SQL is refused exactly as the API
 * refuses it. The subject and the text part render without HTML escaping — neither is markup — while the HTML part
 * escapes by default; unescaped output there was already refused by the policy. Every part shares one
 * {@link RenderBudget}, so limits apply to the whole render, not one part at a time.
 *
 * <p>A part that is absent is not compiled: the command contract lets a sender supply an HTML body, a text body or
 * both, so a text-only email renders a null HTML part rather than failing on an engine given nothing to compile.
 */
public class JmustacheTemplateRenderer implements TemplateRenderer {

    private final RenderLimits limits;

    public JmustacheTemplateRenderer(RenderLimits limits) {
        this.limits = limits;
    }

    @Override
    public RenderedParts render(String subject, String html, String text, Map<String, Object> variables) {
        TemplatePolicy.validateSubject(subject, limits);
        TemplatePolicy.validatePart("html", html, limits, false);
        if (text != null) {
            TemplatePolicy.validatePart("text", text, limits, true);
        }

        Map<String, Object> model = variables == null ? Map.of() : variables;
        RenderBudget budget = new RenderBudget(limits);

        String renderedSubject = renderPart("subject", subject, false, model, budget);
        if (renderedSubject.contains("\r") || renderedSubject.contains("\n")) {
            throw new TemplateLimitExceededException("a rendered subject may not contain a line break");
        }
        if (renderedSubject.length() > TemplatePolicy.SUBJECT_LIMIT) {
            throw new TemplateLimitExceededException("subject length");
        }

        String renderedHtml = html == null ? null : renderPart("html", html, true, model, budget);
        String renderedText = text == null ? null : renderPart("text", text, false, model, budget);
        return new RenderedParts(renderedSubject, renderedHtml, renderedText);
    }

    private String renderPart(
            String part, String source, boolean escapeHtml, Map<String, Object> model, RenderBudget budget) {
        CountingWriter writer = new CountingWriter(budget);
        try {
            Template template = Mustache.compiler()
                    .escapeHTML(escapeHtml)
                    .strictSections(true)
                    .compile(source);
            template.execute(new LimitedModel(model, budget), writer);
        } catch (MustacheException.Context failure) {
            // JMustache wraps whatever the model threw; the caller needs the refusal itself, not the engine's frame.
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw failure;
        } catch (MustacheException failure) {
            throw new TemplateLimitExceededException("the " + part + " part could not be rendered: " + failure.getMessage());
        }
        budget.checkClock();
        return writer.value();
    }
}
