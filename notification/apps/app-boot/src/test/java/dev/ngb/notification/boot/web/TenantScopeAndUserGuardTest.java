package dev.ngb.notification.boot.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The scope rule per endpoint group, which the permission codes alone cannot express.
 *
 * <p>Three groups, three answers: templates are platform-only (a template is the suite's content), settings and the
 * dispatch ledger are tenant-only, and the inbox and the caller's own preference accept either scope as long as the
 * caller is a user. The platform scope must not become a third door onto the tenant surfaces, and a tenant scope must
 * not reach the shared content.
 */
class TenantScopeAndUserGuardTest {

    private static final Actor TENANT_USER = actor(ActorType.USER, PermissionScope.TENANT);
    private static final Actor PLATFORM_USER = actor(ActorType.USER, PermissionScope.PLATFORM);
    private static final Actor MACHINE = actor(ActorType.TENANT_SYSTEM, PermissionScope.TENANT);

    @Test
    void aTenantScopeCannotEditTheSuitesTemplates() {
        assertThatThrownBy(() -> handle(TENANT_USER, "GET", "/api/v1/templates"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("platform scope only");
    }

    @Test
    void aPlatformScopeMayEditTheSuitesTemplates() {
        assertThatCode(() -> handle(PLATFORM_USER, "PUT", "/api/v1/templates/access-granted/en"))
                .doesNotThrowAnyException();
    }

    @Test
    void thePlatformScopeIsRefusedOnTheTenantSettingsAndLedger() {
        assertThatThrownBy(() -> handle(PLATFORM_USER, "GET", "/api/v1/email-dispatches"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("tenant-scoped");
        assertThatThrownBy(() -> handle(PLATFORM_USER, "GET", "/api/v1/settings"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("tenant-scoped");
    }

    @Test
    void aTenantUserMayReadTheTenantSettingsAndLedger() {
        assertThatCode(() -> handle(TENANT_USER, "GET", "/api/v1/settings")).doesNotThrowAnyException();
        assertThatCode(() -> handle(TENANT_USER, "GET", "/api/v1/email-dispatches")).doesNotThrowAnyException();
    }

    @Test
    void theInboxAcceptsBothScopesForAUser() {
        assertThatCode(() -> handle(TENANT_USER, "GET", "/api/v1/notifications")).doesNotThrowAnyException();
        assertThatCode(() -> handle(PLATFORM_USER, "GET", "/api/v1/notifications")).doesNotThrowAnyException();
        assertThatCode(() -> handle(PLATFORM_USER, "GET", "/api/v1/notifications/preferences"))
                .doesNotThrowAnyException();
    }

    @Test
    void aMachineCredentialHasNoInbox() {
        assertThatThrownBy(() -> handle(MACHINE, "GET", "/api/v1/notifications"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("belongs to a user");
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the guard for an actor in the scope its own permission scope implies. */
    private static void handle(Actor actor, String method, String uri) {
        TenantContext context = new FixedScope(scopeFor(actor));
        CurrentActor currentActor = () -> actor;
        new TenantScopeAndUserGuard(context, currentActor)
                .preHandle(new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), new Object());
    }

    private static RequestScope scopeFor(Actor actor) {
        return actor.scope() == PermissionScope.PLATFORM
                ? RequestScope.platform()
                : RequestScope.tenant(new TenantId(UUID.randomUUID()));
    }

    private static Actor actor(ActorType type, PermissionScope scope) {
        return new Actor(type, UUID.randomUUID(), RequestScope.PLATFORM_TENANT_ID, scope, "Test", Set.of());
    }

    private record FixedScope(RequestScope scope) implements TenantContext {

        @Override
        public RequestScope current() {
            return scope;
        }

        @Override
        public <T> T callAs(RequestScope other, Supplier<T> work) {
            return work.get();
        }
    }
}
