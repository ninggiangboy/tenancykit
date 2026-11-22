package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.models.Operation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class CallerLabelCustomizerTest {

    /** A portal annotation the core cannot know about; Billing supplies its own as the implied caller. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface PortalPermission {}

    private static final class Catalog implements CallerCatalog {

        private record Entry(Set<PermissionScope> scopes, boolean grantable) {}

        private final Map<String, Entry> entries = Map.of(
                "t:read", new Entry(Set.of(PermissionScope.TENANT), false),
                "t:manage", new Entry(Set.of(PermissionScope.TENANT), true),
                "p:read", new Entry(Set.of(PermissionScope.PLATFORM), false),
                "i:read", new Entry(Set.of(), false),
                "both:read", new Entry(Set.of(PermissionScope.PLATFORM, PermissionScope.TENANT), false));

        @Override
        public boolean catalogues(String code) {
            return entries.containsKey(code);
        }

        @Override
        public Set<PermissionScope> scopes(String code) {
            return entries.get(code).scopes();
        }

        @Override
        public boolean grantableToTenantSystem(String code) {
            return entries.get(code).grantable();
        }
    }

    static class Fixtures {

        @Callers(Caller.TENANT_USER)
        @RequiresPermission("t:read")
        void tenantRead() {}

        @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
        @RequiresPermission("t:manage")
        void tenantManage() {}

        @Callers(Caller.TENANT_SYSTEM)
        @RequiresPermission("t:read")
        void tenantSystemCannotRead() {}

        @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
        @RequiresPermission("t:read")
        void overDeclared() {}

        @Callers(Caller.PLATFORM_USER)
        @RequiresPermission("both:read")
        void administratorSplit() {}

        @Callers(Caller.TENANT_USER)
        @RequiresPermission("both:read")
        void tenantSplit() {}

        @Callers(Caller.TENANT_SYSTEM)
        @RequiresPermission("both:read")
        void wrongSplit() {}

        @Callers(Caller.INTERNAL)
        @RequiresPermission("i:read")
        void internal() {}

        @Callers(Caller.PLATFORM_USER)
        @RequiresPermission("p:read")
        void platformRead() {}

        @Callers({Caller.PLATFORM_USER, Caller.TENANT_USER})
        void bothPeople() {}

        @RequiresPermission("t:read")
        void noSource() {}

        @Callers({})
        @RequiresPermission("t:read")
        void noCaller() {}

        @Callers(Caller.TENANT_USER)
        @RequiresPermission("unknown:code")
        void unknownCode() {}

        @PublicEndpoint(caller = Caller.PROVIDER)
        void webhook() {}

        @PublicEndpoint
        void anonymous() {}

        @PortalPermission
        void portal() {}

        void noSourceAtAll() {}
    }

    @Callers(Caller.TENANT_USER)
    static class ClassLevel {
        @RequiresPermission("t:read")
        void inherited() {}

        @Callers(Caller.PUBLIC)
        void exception() {}
    }

    private final CallerLabelCustomizer customizer = new CallerLabelCustomizer(
            new Catalog(), Map.of(PortalPermission.class, Caller.CUSTOMER_SESSION));

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new Fixtures(), Fixtures.class.getDeclaredMethod(method));
    }

    private static HandlerMethod classLevel(String method) throws NoSuchMethodException {
        return new HandlerMethod(new ClassLevel(), ClassLevel.class.getDeclaredMethod(method));
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Operation operation) {
        return (List<String>) operation.getExtensions().get(CallerLabelCustomizer.X_ALLOWED_CALLERS);
    }

    @Test
    void aPermissionCodedRouteCarriesItsCallersAndItsPermission() throws Exception {
        Operation operation = new Operation().description("Reads a wallet.");
        customizer.customize(operation, handler("tenantRead"));

        assertThat(labels(operation)).containsExactly("tenant-user");
        assertThat(operation.getExtensions()).containsEntry(CallerLabelCustomizer.X_REQUIRED_PERMISSION, "t:read");
        assertThat(operation.getDescription()).isEqualTo("Reads a wallet.\n\nCallers: tenant-user. Requires `t:read`.");
    }

    @Test
    void aGrantableCodeDeclaresBothCallers() throws Exception {
        Operation operation = new Operation();
        customizer.customize(operation, handler("tenantManage"));

        assertThat(labels(operation)).containsExactly("tenant-user", "tenant-system");
        assertThat(operation.getDescription()).isEqualTo("Callers: tenant-user, tenant-system. Requires `t:manage`.");
    }

    @Test
    void aCodeWithBothScopesMayBeSplitIntoOneCallerPerRoute() throws Exception {
        Operation administrator = new Operation();
        Operation tenant = new Operation();
        customizer.customize(administrator, handler("administratorSplit"));
        customizer.customize(tenant, handler("tenantSplit"));

        assertThat(labels(administrator)).containsExactly("platform-user");
        assertThat(labels(tenant)).containsExactly("tenant-user");
    }

    @Test
    void aCallerTheCatalogDoesNotAllowFailsInBothDirections() {
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("tenantSystemCannotRead")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog allows only");
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("overDeclared")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog allows");
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("wrongSplit")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog allows only");
    }

    @Test
    void anInternalCodeIsInternal() throws Exception {
        Operation operation = new Operation();
        customizer.customize(operation, handler("internal"));

        assertThat(labels(operation)).containsExactly("internal");
    }

    @Test
    void aPublicEndpointTakesItsCallerFromTheAnnotation() throws Exception {
        Operation webhook = new Operation();
        Operation anonymous = new Operation();
        customizer.customize(webhook, handler("webhook"));
        customizer.customize(anonymous, handler("anonymous"));

        assertThat(labels(webhook)).containsExactly("provider");
        assertThat(webhook.getDescription()).isEqualTo("Callers: provider.");
        assertThat(labels(anonymous)).containsExactly("public");
    }

    @Test
    void anImpliedAnnotationResolvesToItsCaller() throws Exception {
        Operation operation = new Operation();
        customizer.customize(operation, handler("portal"));

        assertThat(labels(operation)).containsExactly("customer-session");
    }

    @Test
    void theClassIsTheDefaultAndAMethodIsTheException() throws Exception {
        Operation inherited = new Operation();
        Operation exception = new Operation();
        customizer.customize(inherited, classLevel("inherited"));
        customizer.customize(exception, classLevel("exception"));

        assertThat(labels(inherited)).containsExactly("tenant-user");
        assertThat(labels(exception)).containsExactly("public");
    }

    @Test
    void aHandlerWithNoSourceFailsWithTheHandlerNamed() {
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("noSourceAtAll")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No caller is declared");
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("noSource")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No caller is declared");
    }

    @Test
    void anEmptyDeclarationAndAnUnknownCodeFail() {
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("noCaller")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names no caller");
        assertThatThrownBy(() -> customizer.customize(new Operation(), handler("unknownCode")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in no permission catalog");
    }

    @Test
    void theWireValuesAreTheSevenKinds() {
        assertThat(java.util.Arrays.stream(Caller.values()).map(Caller::wire))
                .containsExactly(
                        "platform-user", "tenant-user", "tenant-system", "customer-session", "public", "provider", "internal");
        assertThat(Caller.fromWire("customer-session")).contains(Caller.CUSTOMER_SESSION);
        assertThat(Caller.fromWire("audience")).isEmpty();
    }

    @Test
    void theCatalogReaderIsNotNeededForAnUncodedRoute() throws Exception {
        Operation operation = new Operation();
        customizer.customize(operation, handler("bothPeople"));

        assertThat(labels(operation)).containsExactly("platform-user", "tenant-user");
        assertThat(operation.getDescription()).isEqualTo("Callers: platform-user, tenant-user.");
    }
}
