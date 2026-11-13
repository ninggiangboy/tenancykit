package dev.ngb.core.spring.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The scoped tenant context: the scope is bound for exactly the duration of a call, restored afterwards, invisible to
 * another thread, and never silently absent.
 */
class ScopedTenantContextTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final ScopedTenantContext context = new ScopedTenantContext();

    @Test
    void noBoundScopeIsAnError() {
        assertThatThrownBy(context::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no request scope");
    }

    @Test
    void aCallSeesTheScopeAndTheScopeIsGoneAfterwards() {
        RequestScope scope = RequestScope.tenant(TENANT);

        assertThat(context.callAs(scope, context::current)).isEqualTo(scope);

        assertThatThrownBy(context::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNestedCallRestoresTheOuterScope() {
        RequestScope outer = RequestScope.tenant(TENANT);
        RequestScope inner = RequestScope.platform();

        RequestScope seen = context.callAs(outer, () -> context.callAs(inner, context::current));

        assertThat(seen).isEqualTo(inner);
        assertThat(context.callAs(outer, context::current)).isEqualTo(outer);
    }

    @Test
    void aFailedCallStillReleasesTheScope() {
        RequestScope scope = RequestScope.tenant(TENANT);

        assertThatThrownBy(() -> context.callAs(scope, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(context::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theScopeIsInvisibleToAnotherThread() {
        RequestScope scope = RequestScope.tenant(TENANT);
        AtomicReference<Throwable> inOtherThread = new AtomicReference<>();

        context.runAs(scope, () -> {
            Thread other = new Thread(() -> {
                try {
                    context.current();
                } catch (RuntimeException e) {
                    inOtherThread.set(e);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(inOtherThread.get()).isInstanceOf(IllegalStateException.class);
        assertThat(context.callAs(scope, context::current)).as("the calling thread kept its own scope").isEqualTo(scope);
    }

    @Test
    void requireTenantAnswersTheTenantsIdAndRejectsThePlatformScope() {
        assertThat(context.callAs(RequestScope.tenant(TENANT), context::requireTenant)).isEqualTo(TENANT);
        assertThatThrownBy(() -> context.callAs(RequestScope.platform(), context::requireTenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant scope");
    }
}
