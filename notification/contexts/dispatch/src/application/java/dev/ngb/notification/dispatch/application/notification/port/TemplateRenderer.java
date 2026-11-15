package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.dispatch.domain.template.RenderedMessage;
import dev.ngb.notification.shared.TemplateKey;
import java.util.Map;

/**
 * Renders one message, failing rather than sending a half-filled one.
 *
 * <p>The implementation is an adapter because a template engine is a technology; the application names the port so
 * its consumers stay plain Java.
 */
public interface TemplateRenderer {

    /**
     * @throws dev.ngb.notification.dispatch.application.notification.exception.MissingTemplateVariableException when a
     *     template names a variable the model does not carry
     * @throws dev.ngb.notification.dispatch.application.notification.exception.ForbiddenTemplateConstructException
     *     when a part contains a section, inverted section or partial
     * @throws dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException when a part
     *     or the subject exceeds its limit
     */
    RenderedMessage render(TemplateKey key, String locale, Map<String, Object> variables);
}
