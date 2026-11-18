package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.port.TemplateOverrideRepository;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.application.notification.service.TemplatePolicy;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import dev.ngb.notification.shared.TemplateKey;

/**
 * Saves or deletes the suite-wide override for a template and locale.
 *
 * <p>The same {@link TemplatePolicy} the renderer uses is applied here, so the API and the read path refuse exactly the
 * same input; a row inserted directly with SQL is still refused at load time.
 *
 * <p>There is no tenant to take: the row serves every tenant, which is why the endpoint is only reachable at platform
 * scope.
 */
public class SaveTemplateOverrideUseCase {

    private final UnitOfWork unitOfWork;
    private final TemplateOverrideRepository overrides;
    private final int maxPartBytes;

    public SaveTemplateOverrideUseCase(
            UnitOfWork unitOfWork, TemplateOverrideRepository overrides, int maxPartBytes) {
        this.unitOfWork = unitOfWork;
        this.overrides = overrides;
        this.maxPartBytes = maxPartBytes;
    }

    public void save(String key, String locale, TemplateVariant variant) {
        NotificationType.fromTemplateKey(key)
                .orElseThrow(() -> new NotFoundException("No notification type owns this template key."));
        if (!LocalePolicy.SUPPORTED.contains(locale)) {
            throw new BadRequestException("the locale must be one of " + LocalePolicy.SUPPORTED);
        }
        validate("email.subject", variant.subject());
        validate("email.html", variant.html());
        validate("email.text", variant.text());
        validate("inapp.title", variant.title());
        validate("inapp.body", variant.body());
        TemplateKey templateKey = new TemplateKey(key);
        unitOfWork.transactional(() -> {
            overrides.save(templateKey, locale, variant);
            return null;
        });
    }

    public void delete(String key, String locale) {
        NotificationType.fromTemplateKey(key)
                .orElseThrow(() -> new NotFoundException("No notification type owns this template key."));
        if (!LocalePolicy.SUPPORTED.contains(locale)) {
            throw new BadRequestException("the locale must be one of " + LocalePolicy.SUPPORTED);
        }
        TemplateKey templateKey = new TemplateKey(key);
        boolean deleted = unitOfWork.transactional(() -> overrides.delete(templateKey, locale));
        if (!deleted) {
            throw new NotFoundException("There is no override for that template and locale.");
        }
    }

    private void validate(String part, String value) {
        if (value != null) {
            TemplatePolicy.validatePart(part, value, maxPartBytes);
        }
    }
}
