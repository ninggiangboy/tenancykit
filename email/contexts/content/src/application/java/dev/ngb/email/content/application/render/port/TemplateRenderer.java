package dev.ngb.email.content.application.render.port;

import java.util.Map;

/** Renders the three parts of one template with a bounded sandbox. */
public interface TemplateRenderer {

    RenderedParts render(String subject, String html, String text, Map<String, Object> variables);
}
