package dev.ngb.core.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.Authorizer;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/**
 * The one permission interceptor: an endpoint that declares a code asks for exactly that code, an endpoint that
 * declares neither a code nor {@link Authenticated}/{@link PublicEndpoint} fails closed, and a service check answers
 * for the endpoints only it knows.
 */
class PermissionInterceptorTest {

    private final List<String> required = new ArrayList<>();
    private final Authorizer authorizer = required::add;

    private static final Controller CONTROLLER = new Controller();

    private static HandlerMethod handler(String method) {
        try {
            Method declared = Controller.class.getDeclaredMethod(method);
            return new HandlerMethod(CONTROLLER, declared);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aDeclaredCodeIsExactlyWhatTheAuthorizerIsAskedFor() {
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer);

        assertThat(interceptor.preHandle(null, null, handler("read"))).isTrue();

        assertThat(required).containsExactly("billing:invoice:read");
    }

    @Test
    void anAuthenticatedEndpointSkipsTheAuthorizer() {
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer);

        assertThat(interceptor.preHandle(null, null, handler("anyUser"))).isTrue();
        assertThat(interceptor.preHandle(null, null, handler("open"))).isTrue();

        assertThat(required).isEmpty();
    }

    @Test
    void anEndpointThatDeclaresNothingFailsClosed() {
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer);

        assertThatThrownBy(() -> interceptor.preHandle(null, null, handler("unannotated")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no access rule");

        assertThat(required).as("a forgotten annotation must not reach the authorizer").isEmpty();
    }

    @Test
    void aServiceCheckAnswersForTheEndpointItKnows() {
        PermissionCheck portal = method -> {
            if (!method.getMethod().getName().equals("custom")) {
                return false;
            }
            throw new AccessDeniedException("no portal session");
        };
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer, List.of(portal));

        assertThat(catchThrowable(() -> interceptor.preHandle(null, null, handler("custom"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(interceptor.preHandle(null, null, handler("read"))).isTrue();
        assertThat(required).containsExactly("billing:invoice:read");
    }

    @Test
    void aCheckThatRecognisesNothingLetsTheNextOneRun() {
        PermissionCheck first = method -> false;
        PermissionCheck second = method -> {
            throw new AccessDeniedException("refused by the second check");
        };
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer, List.of(first, second));

        assertThat(catchThrowable(() -> interceptor.preHandle(null, null, handler("read"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(required).isEmpty();
    }

    @Test
    void aHandlerThatIsNotAControllerMethodPassesThrough() {
        PermissionInterceptor interceptor = new PermissionInterceptor(authorizer);

        assertThat(interceptor.preHandle(null, null, new Object())).isTrue();
        assertThat(interceptor.preHandle(null, null, null)).isTrue();

        assertThat(required).isEmpty();
    }

    @SuppressWarnings("unused")
    private static final class Controller {

        @RequiresPermission("billing:invoice:read")
        void read() {}

        @Authenticated
        void anyUser() {}

        @PublicEndpoint
        void open() {}

        void unannotated() {}

        void custom() {}
    }
}
