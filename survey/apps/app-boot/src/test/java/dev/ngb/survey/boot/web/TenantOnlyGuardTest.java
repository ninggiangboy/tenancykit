package dev.ngb.survey.boot.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Every survey endpoint is tenant data, so the platform scope is refused even though the entitlement filter admits it. */
class TenantOnlyGuardTest {

    @Test
    void aTenantScopeMayCallASurveyEndpoint() {
        assertThatCode(() -> handle(RequestScope.tenant(new TenantId(UUID.randomUUID()))))
                .doesNotThrowAnyException();
    }

    @Test
    void thePlatformScopeIsRefused() {
        assertThatThrownBy(() -> handle(RequestScope.platform()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("tenant-scoped");
    }

    private static void handle(RequestScope scope) {
        TenantContext context = new FixedScope(scope);
        new TenantOnlyGuard(context).preHandle(
                new MockHttpServletRequest("GET", "/api/v1/ping"), new MockHttpServletResponse(), new Object());
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
