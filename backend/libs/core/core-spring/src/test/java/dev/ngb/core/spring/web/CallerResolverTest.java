package dev.ngb.core.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.SelfService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class CallerResolverTest {

    /** A portal annotation the core cannot know about; a service supplies its own as the implied caller. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface PortalPermission {}

    static class Fixtures {

        @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
        void bothTenantKinds() {}

        @Callers(value = Caller.TENANT_SYSTEM, prefix = Callers.Prefix.NONE)
        void standardPath() {}

        @SelfService
        void selfService() {}

        @PublicEndpoint(caller = Caller.PROVIDER)
        void webhook() {}

        @PortalPermission
        void portal() {}

        @Callers({})
        void empty() {}

        @Callers({Caller.TENANT_USER, Caller.TENANT_USER})
        void repeated() {}

        void nothing() {}
    }

    @Callers(Caller.PLATFORM_USER)
    static class ClassLevel {
        void inherited() {}

        @Callers(Caller.PUBLIC)
        void exception() {}
    }

    private final CallerResolver resolver = new CallerResolver(Map.of(PortalPermission.class, Caller.CUSTOMER_SESSION));

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new Fixtures(), Fixtures.class.getDeclaredMethod(method));
    }

    private static HandlerMethod classLevel(String method) throws NoSuchMethodException {
        return new HandlerMethod(new ClassLevel(), ClassLevel.class.getDeclaredMethod(method));
    }

    @Test
    void theMethodDeclaresBothTenantKinds() throws Exception {
        assertThat(resolver.resolve(handler("bothTenantKinds")))
                .containsExactlyInAnyOrder(Caller.TENANT_USER, Caller.TENANT_SYSTEM);
    }

    @Test
    void theClassIsTheDefaultAndAMethodIsTheException() throws Exception {
        assertThat(resolver.resolve(classLevel("inherited"))).containsExactly(Caller.PLATFORM_USER);
        assertThat(resolver.resolve(classLevel("exception"))).containsExactly(Caller.PUBLIC);
    }

    @Test
    void selfServiceIsBothPersonKinds() throws Exception {
        assertThat(resolver.resolve(handler("selfService")))
                .containsExactlyInAnyOrder(Caller.PLATFORM_USER, Caller.TENANT_USER);
    }

    @Test
    void aPublicEndpointTakesItsCallerFromTheAnnotation() throws Exception {
        assertThat(resolver.resolve(handler("webhook"))).containsExactly(Caller.PROVIDER);
    }

    @Test
    void anImpliedAnnotationResolvesToItsCaller() throws Exception {
        assertThat(resolver.resolve(handler("portal"))).containsExactly(Caller.CUSTOMER_SESSION);
    }

    @Test
    void anEmptyDeclarationAndARepeatedCallerFail() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(handler("empty")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names no caller");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(handler("repeated")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repeats a caller");
    }

    @Test
    void aHandlerWithNoSourceResolvesToNothing() throws Exception {
        assertThat(resolver.resolve(handler("nothing"))).isEmpty();
    }

    @Test
    void aStandardPathKeepsThePathItDeclares() throws Exception {
        assertThat(CallerResolver.keepsThePath(handler("standardPath"))).isTrue();
        assertThat(CallerResolver.keepsThePath(handler("bothTenantKinds"))).isFalse();
    }
}
