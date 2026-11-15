package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.shared.TemplateKey;

/**
 * Writes and deletes the suite-wide override; the read path is {@link TemplateSource}.
 *
 * <p>There is no tenant parameter: one row per {@code (template_key, locale)} serves every tenant, and only a Platform
 * user at platform scope may edit it.
 */
public interface TemplateOverrideRepository {

    void save(TemplateKey key, String locale, TemplateVariant variant);

    boolean delete(TemplateKey key, String locale);
}
