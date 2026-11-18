package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.port.DefaultTemplates;
import dev.ngb.notification.dispatch.application.notification.port.TemplateSource;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import dev.ngb.notification.shared.TemplateKey;

/** The variant a given key and locale renders from: the shared override's parts where defined, the default elsewhere. */
public class ResolveTemplateUseCase {

    public record Resolved(String key, String locale, TemplateVariant variant, boolean overridden) {}

    private final UnitOfWork unitOfWork;
    private final TemplateSource overrides;
    private final DefaultTemplates defaults;

    public ResolveTemplateUseCase(
            UnitOfWork unitOfWork, TemplateSource overrides, DefaultTemplates defaults) {
        this.unitOfWork = unitOfWork;
        this.overrides = overrides;
        this.defaults = defaults;
    }

    public Resolved resolve(String key, String locale) {
        NotificationType.fromTemplateKey(key)
                .orElseThrow(() -> new NotFoundException("No notification type owns this template key."));
        String checkedLocale = locale == null || locale.isBlank() ? LocalePolicy.FALLBACK : locale;
        if (!LocalePolicy.SUPPORTED.contains(checkedLocale)) {
            throw new NotFoundException("This template has no variant for that locale.");
        }
        TemplateKey templateKey = new TemplateKey(key);
        return unitOfWork.transactional(() -> {
            TemplateVariant base = defaults.find(templateKey, checkedLocale);
            TemplateVariant override = overrides.find(templateKey, checkedLocale).orElse(null);
            if (override == null) {
                return new Resolved(key, checkedLocale, base, false);
            }
            TemplateVariant merged = new TemplateVariant(
                    choose(override.subject(), base.subject()),
                    choose(override.html(), base.html()),
                    choose(override.text(), base.text()),
                    choose(override.title(), base.title()),
                    choose(override.body(), base.body()),
                    override.version());
            return new Resolved(key, checkedLocale, merged, true);
        });
    }

    private static String choose(String override, String base) {
        return override == null ? base : override;
    }
}
