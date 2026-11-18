package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.shared.TemplateKey;
import java.util.Optional;

/**
 * The suite-wide override row for a template and locale; empty means "use the classpath default".
 *
 * <p>There is no tenant parameter: a template is the suite's content, and every tenant renders from the same row.
 */
public interface TemplateSource {

    Optional<TemplateVariant> find(TemplateKey key, String locale);
}
