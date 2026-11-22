package dev.ngb.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.sessions.AuthenticationSessionModel;

class TenantFlowContextTest {

    @Test
    void theTypedEmailIsScopedToTheTenant() {
        MultivaluedMap<String, String> scoped = TenantFlowContext.scoped(Optional.of("acme"), form("  an@acme.vn "));

        assertEquals("acme:an@acme.vn", scoped.getFirst("username"));
    }

    @Test
    void theRequestsOwnFormDataIsNotChanged() {
        MultivaluedMap<String, String> original = form("an@acme.vn");

        TenantFlowContext.scoped(Optional.of("acme"), original);

        assertEquals("an@acme.vn", original.getFirst("username"));
    }

    @Test
    void anEmptyUsernameStaysEmptySoTheStockMessageStillApplies() {
        assertEquals("", TenantFlowContext.scoped(Optional.of("acme"), form("")).getFirst("username"));
        assertEquals(" ", TenantFlowContext.scoped(Optional.of("acme"), form(" ")).getFirst("username"));
    }

    @Test
    void withoutATenantTheFormIsLeftAlone() {
        MultivaluedMap<String, String> original = form("acme:an@acme.vn");

        assertSame(original, TenantFlowContext.scoped(Optional.empty(), original));
    }

    @Test
    void aTypedOtherTenantEndsUpInsideThisTenantAndFindsNoOne() {
        assertEquals("acme:other:an@x.vn",
                TenantFlowContext.scoped(Optional.of("acme"), form("other:an@x.vn")).getFirst("username"));
    }

    @Test
    void theWrappedContextHandsTheStockStepTheScopedUsername() {
        AuthenticationFlowContext context = context("acme", form("an@acme.vn"), new ArrayList<>());

        String seen = TenantFlowContext.scopingUsername(context)
                .getHttpRequest().getDecodedFormParameters().getFirst("username");

        assertEquals("acme:an@acme.vn", seen);
    }

    @Test
    void theWrappedContextWithoutATenantIsTheContextItself() {
        AuthenticationFlowContext context = context(null, form("an@acme.vn"), new ArrayList<>());

        assertSame(context, TenantFlowContext.scopingUsername(context));
        assertSame(context, TenantFlowContext.announcing(context));
    }

    @Test
    void everyFormTheWrappedContextBuildsNamesTheHost() {
        List<String> attributes = new ArrayList<>();
        AuthenticationFlowContext context = context("acme", form(""), attributes);

        TenantFlowContext.announcing(context).form();
        TenantFlowContext.announcing(context).form();

        assertEquals(List.of("suiteTenant=acme.example.com", "suiteTenant=acme.example.com"), attributes);
    }

    private static MultivaluedMap<String, String> form(String username) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("username", username);
        return form;
    }

    private static AuthenticationFlowContext context(String tenant, MultivaluedMap<String, String> form, List<String> attributes) {
        AuthenticationSessionModel session = proxy(AuthenticationSessionModel.class, (method, args) -> switch (method) {
            case "getClientNote" -> tenant;
            case "getRedirectUri" -> "http://acme.example.com/console/";
            default -> throw new UnsupportedOperationException(method);
        });
        HttpRequest request = proxy(HttpRequest.class, (method, args) -> switch (method) {
            case "getDecodedFormParameters" -> form;
            default -> throw new UnsupportedOperationException(method);
        });
        LoginFormsProvider[] provider = new LoginFormsProvider[1];
        provider[0] = proxy(LoginFormsProvider.class, (method, args) -> {
            if (method.equals("setAttribute")) {
                attributes.add(args[0] + "=" + args[1]);
                return provider[0];
            }
            throw new UnsupportedOperationException(method);
        });
        return proxy(AuthenticationFlowContext.class, (method, args) -> switch (method) {
            case "getAuthenticationSession" -> session;
            case "getHttpRequest" -> request;
            case "form" -> provider[0];
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private interface Answer {
        Object to(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> answer.to(method.getName(), args)));
    }
}
