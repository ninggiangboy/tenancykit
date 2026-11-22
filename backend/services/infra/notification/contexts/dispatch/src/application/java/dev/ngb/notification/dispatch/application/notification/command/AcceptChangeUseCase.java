package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.port.NotificationPreferenceRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationSettingsRepository;
import dev.ngb.notification.dispatch.application.notification.port.TemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.service.AudienceResolver;
import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import dev.ngb.notification.dispatch.application.notification.service.catalog.NotificationCatalog;
import dev.ngb.notification.dispatch.application.notification.service.catalog.NotificationCatalog.ResolvedNotification;
import dev.ngb.notification.dispatch.domain.notification.Notification;
import dev.ngb.notification.dispatch.domain.notification.NotificationId;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import dev.ngb.notification.dispatch.domain.template.RenderedMessage;
import dev.ngb.notification.platform.api.replica.TenantDirectory;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.platform.api.replica.UserView;
import java.util.List;
import java.util.Optional;

/**
 * One applied Platform event becomes the rendered in-app notifications the transition deserves.
 *
 * <p>It runs inside the replica handler's transaction: the replica upsert, this decision, the in-app rows and the
 * email dispatch rows commit together or not at all. A tenant settings row that disables the type produces nothing,
 * and a repeated transition is stopped by the notification table's unique key, not by memory.
 */
public class AcceptChangeUseCase {

    /** How many recipients were written and how many were skipped as already present. */
    public record Result(int accepted, int skipped, boolean silenced) {

        static Result none() {
            return new Result(0, 0, true);
        }
    }

    private final UnitOfWork unitOfWork;
    private final TenantDirectory tenants;
    private final NotificationCatalog catalog;
    private final AudienceResolver audience;
    private final TemplateRenderer renderer;
    private final NotificationSettingsRepository settings;
    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notifications;
    private final EmailDispatchUseCase emailDispatch;
    private final Clock clock;
    private final IdGenerator ids;

    public AcceptChangeUseCase(
            UnitOfWork unitOfWork,
            TenantDirectory tenants,
            NotificationCatalog catalog,
            AudienceResolver audience,
            TemplateRenderer renderer,
            NotificationSettingsRepository settings,
            NotificationPreferenceRepository preferences,
            NotificationRepository notifications,
            EmailDispatchUseCase emailDispatch,
            Clock clock,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.catalog = catalog;
        this.audience = audience;
        this.renderer = renderer;
        this.settings = settings;
        this.preferences = preferences;
        this.notifications = notifications;
        this.emailDispatch = emailDispatch;
        this.clock = clock;
        this.ids = ids;
    }

    public Result accept(Change change) {
        return unitOfWork.transactional(() -> {
            NotificationSettingsView tenantSettings = settings.find(change.tenantId());
            Optional<TenantView> maybeTenant = tenants.tenant(change.tenantId());
            if (maybeTenant.isEmpty()) {
                return Result.none();
            }
            TenantView tenant = maybeTenant.get();
            Optional<ResolvedNotification> maybeResolved = catalog.resolve(change, tenant);
            if (maybeResolved.isEmpty() || !tenantSettings.enabled(maybeResolved.get().type())) {
                return Result.none();
            }
            ResolvedNotification resolved = maybeResolved.get();
            List<UserView> recipients = audience.recipients(resolved.type(), change);
            int accepted = 0;
            int skipped = 0;
            for (UserView recipient : recipients) {
                PreferenceView preference = preferences.find(change.tenantId(), recipient.userId());
                String locale = LocalePolicy.choose(preference.locale(), tenantSettings.defaultLocale());
                RenderedMessage message =
                        renderer.render(new dev.ngb.notification.shared.TemplateKey(resolved.type().templateKey()),
                                locale, resolved.variables());
                Notification notification = new Notification(
                        new NotificationId(ids.newId()),
                        change.tenantId(),
                        recipient.userId(),
                        resolved.type(),
                        change.eventId(),
                        change.eventType(),
                        message.templateKey().value(),
                        message.templateVersion(),
                        locale,
                        message.title(),
                        message.body(),
                        NotificationLinks.INBOX,
                        null,
                        clock.now());
                if (notifications.insertIfAbsent(notification)) {
                    accepted++;
                    emailDispatch.dispatch(change, message, recipient, preference, tenant);
                } else {
                    skipped++;
                }
            }
            return new Result(accepted, skipped, false);
        });
    }
}
