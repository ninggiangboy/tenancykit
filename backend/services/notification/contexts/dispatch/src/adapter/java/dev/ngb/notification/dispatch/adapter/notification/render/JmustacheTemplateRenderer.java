package dev.ngb.notification.dispatch.adapter.notification.render;

import com.samskivert.mustache.Mustache;
import dev.ngb.notification.dispatch.application.notification.port.DefaultTemplates;
import dev.ngb.notification.dispatch.application.notification.port.TemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.port.TemplateSource;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.application.notification.service.TemplatePolicy;
import dev.ngb.notification.dispatch.domain.template.RenderedMessage;
import dev.ngb.notification.shared.TemplateKey;
import java.util.Map;

/**
 * Renders one variant part by part: the shared override where it defines a part, the classpath default elsewhere.
 *
 * <p>The renderer is pure: the same variant and the same variables give the same bytes. It fails rather than sending
 * a half-filled message — an unknown variable, a forbidden construct and an oversize part are all hard errors.
 */
public class JmustacheTemplateRenderer implements TemplateRenderer {

    /** The email contract's subject limit, kept here for callers that only need the number. */
    public static final int SUBJECT_LIMIT = TemplatePolicy.SUBJECT_LIMIT;

    private final TemplateSource overrides;
    private final DefaultTemplates defaults;
    private final int maxPartBytes;

    public JmustacheTemplateRenderer(TemplateSource overrides, DefaultTemplates defaults, int maxPartBytes) {
        this.overrides = overrides;
        this.defaults = defaults;
        this.maxPartBytes = maxPartBytes;
    }

    @Override
    public RenderedMessage render(TemplateKey key, String locale, Map<String, Object> variables) {
        TemplateVariant base = defaults.find(key, locale);
        TemplateVariant override = overrides.find(key, locale).orElse(null);
        String subject = choose(override == null ? null : override.subject(), base.subject());
        String html = choose(override == null ? null : override.html(), base.html());
        String text = choose(override == null ? null : override.text(), base.text());
        String title = choose(override == null ? null : override.title(), base.title());
        String body = choose(override == null ? null : override.body(), base.body());

        TemplatePolicy.validatePart("email.subject", subject, maxPartBytes);
        TemplatePolicy.validatePart("email.html", html, maxPartBytes);
        TemplatePolicy.validatePart("email.text", text, maxPartBytes);
        TemplatePolicy.validatePart("inapp.title", title, maxPartBytes);
        TemplatePolicy.validatePart("inapp.body", body, maxPartBytes);

        String renderedSubject = renderPart("email.subject", subject, EscapeMode.NONE, variables);
        if (renderedSubject.contains("\n") || renderedSubject.contains("\r")) {
            throw new dev.ngb.core.error.BadRequestException("the rendered subject may not contain a line break");
        }
        if (renderedSubject.length() > SUBJECT_LIMIT) {
            throw new dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException(
                    "email.subject", renderedSubject.length(), SUBJECT_LIMIT);
        }

        int version = override == null ? base.version() : override.version();
        return new RenderedMessage(
                key,
                version,
                locale,
                renderPart("inapp.title", title, EscapeMode.HTML, variables),
                renderPart("inapp.body", body, EscapeMode.HTML, variables),
                renderedSubject,
                renderPart("email.html", html, EscapeMode.HTML, variables),
                renderPart("email.text", text, EscapeMode.NONE, variables));
    }

    private String renderPart(String partName, String source, EscapeMode mode, Map<String, Object> variables) {
        TemplatePolicy.validatePart(partName, source, maxPartBytes);
        try {
            return Mustache.compiler()
                    .escapeHTML(mode.escapes())
                    .strictSections(true)
                    .compile(source)
                    .execute(new StrictTemplateModel(variables));
        } catch (com.samskivert.mustache.MustacheException.Context failure) {
            // JMustache wraps whatever the model threw; the caller needs the refusal itself, not the engine's frame.
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private static String choose(String override, String base) {
        return override == null ? base : override;
    }
}
