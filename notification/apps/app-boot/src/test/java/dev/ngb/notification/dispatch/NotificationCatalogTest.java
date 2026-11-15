package dev.ngb.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessChangedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessDisabledRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.AccessGrantedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.EntitlementChangedRule;
import dev.ngb.notification.dispatch.application.notification.service.catalog.NotificationCatalog;
import dev.ngb.notification.dispatch.application.notification.service.catalog.TenantDeactivatedRule;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.platform.api.replica.UserView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The truth table: which transitions notify, and which silently do not. */
class NotificationCatalogTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final TenantView TENANT_VIEW = new TenantView(TENANT, "acme", "Acme", "ACTIVE");
    private static final UUID EVENT = UUID.randomUUID();

    private final NotificationCatalog catalog = new NotificationCatalog(List.of(
            new AccessGrantedRule(),
            new AccessDisabledRule(),
            new AccessChangedRule(),
            new EntitlementChangedRule(),
            new TenantDeactivatedRule()));

    @Test
    void aFirstActiveGrantNotifies() {
        Change change = userChange(null, user("ACTIVE", Set.of("notification:template:read")));
        assertThat(catalog.resolve(change, TENANT_VIEW)).get()
                .extracting(NotificationCatalog.ResolvedNotification::type)
                .isEqualTo(NotificationType.ACCESS_GRANTED);
    }

    @Test
    void identicalPermissionsDoNotNotify() {
        Set<String> same = Set.of("notification:template:read");
        assertThat(catalog.resolve(userChange(user("ACTIVE", same), user("ACTIVE", same)), TENANT_VIEW)).isEmpty();
    }

    @Test
    void aRealPermissionDifferenceNotifies() {
        Change change = userChange(
                user("ACTIVE", Set.of("notification:template:read", "notification:config:manage")),
                user("ACTIVE", Set.of("notification:template:read")));
        var resolved = catalog.resolve(change, TENANT_VIEW).orElseThrow();
        assertThat(resolved.type()).isEqualTo(NotificationType.ACCESS_CHANGED);
        assertThat(resolved.variables()).containsEntry("removedPermissions", "notification:config:manage");
        assertThat(resolved.variables().get("addedPermissions")).isEqualTo("-");
    }

    @Test
    void anActiveUserBecomingDisabledNotifies() {
        Change change = userChange(user("ACTIVE", Set.of()), user("DISABLED", Set.of()));
        assertThat(catalog.resolve(change, TENANT_VIEW)).get()
                .extracting(NotificationCatalog.ResolvedNotification::type)
                .isEqualTo(NotificationType.ACCESS_DISABLED);
    }

    @Test
    void aPlatformScopedUserGetsTheSameAccessNotifications() {
        UserView platformUser = new UserView(
                UUID.randomUUID(), TenantId.of("00000000-0000-7000-8000-000000000001"), "PLATFORM",
                "a@example.com", "Admin", "ACTIVE", Set.of(), 1);
        assertThat(catalog.resolve(userChange(null, platformUser), TENANT_VIEW)).get()
                .extracting(NotificationCatalog.ResolvedNotification::type)
                .isEqualTo(NotificationType.ACCESS_GRANTED);
    }

    @Test
    void aPlatformScopedUserIsNotifiedWhenItsPermissionsChange() {
        UUID userId = UUID.randomUUID();
        TenantId platform = TenantId.of("00000000-0000-7000-8000-000000000001");
        UserView before = new UserView(
                userId, platform, "PLATFORM", "a@example.com", "Admin", "ACTIVE", Set.of("platform:tenant:read"), 1);
        UserView after = new UserView(
                userId, platform, "PLATFORM", "a@example.com", "Admin", "ACTIVE",
                Set.of("platform:tenant:read", "platform:user:manage"), 2);
        assertThat(catalog.resolve(userChange(before, after), TENANT_VIEW)).get()
                .extracting(NotificationCatalog.ResolvedNotification::type)
                .isEqualTo(NotificationType.ACCESS_CHANGED);
    }

    @Test
    void aNewOrChangedEntitlementNotifiesAndTheSameStateDoesNot() {
        Change created = new Change(EVENT, "entitlement.changed", TENANT, null, null, null, null, "billing", null, true);
        assertThat(catalog.resolve(created, TENANT_VIEW)).get()
                .extracting(NotificationCatalog.ResolvedNotification::type)
                .isEqualTo(NotificationType.ENTITLEMENT_CHANGED);

        Change unchanged = new Change(EVENT, "entitlement.changed", TENANT, null, null, null, null, "billing", true, true);
        assertThat(catalog.resolve(unchanged, TENANT_VIEW)).isEmpty();

        Change disabled = new Change(EVENT, "entitlement.changed", TENANT, null, null, null, null, "billing", true, false);
        assertThat(catalog.resolve(disabled, TENANT_VIEW).orElseThrow().variables())
                .containsEntry("enabled", "disabled");
    }

    @Test
    void aDeactivatedTenantNotifies() {
        TenantView deactivated = new TenantView(TENANT, "acme", "Acme", "DEACTIVATED");
        Change change = new Change(
                EVENT, "tenant.changed", TENANT, null, null, TENANT_VIEW, deactivated, null, null, null);
        var resolved = catalog.resolve(change, deactivated).orElseThrow();
        assertThat(resolved.type()).isEqualTo(NotificationType.TENANT_DEACTIVATED);
        assertThat(resolved.variables()).containsEntry("tenantCode", "acme");
    }

    @Test
    void theRuleOrderIsTheDocumentedOne() {
        assertThat(catalog.order()).containsExactly(
                NotificationType.ACCESS_GRANTED,
                NotificationType.ACCESS_DISABLED,
                NotificationType.ACCESS_CHANGED,
                NotificationType.ENTITLEMENT_CHANGED,
                NotificationType.TENANT_DEACTIVATED);
    }

    private static Change userChange(UserView previous, UserView current) {
        return new Change(EVENT, "user-access.changed", TENANT, previous, current, null, null, null, null, null);
    }

    private static UserView user(String status, Set<String> permissions) {
        return new UserView(
                UUID.randomUUID(), TENANT, "TENANT", "a@example.com", "A", status, permissions, 1);
    }
}
