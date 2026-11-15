package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.shared.TemplateKey;

/** The templates shipped in the classpath, one variant per type and locale. */
public interface DefaultTemplates {

    /** @throws IllegalStateException when the resource tree is incomplete, which is a packaging error */
    TemplateVariant find(TemplateKey key, String locale);
}
