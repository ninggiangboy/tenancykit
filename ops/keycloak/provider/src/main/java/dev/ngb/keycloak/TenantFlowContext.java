package dev.ngb.keycloak;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.services.managers.AuthenticationManager;

/**
 * What the sign-in form and the reset-credentials form share: both belong to a tenant when the authorization request
 * names one, and both then take the email the person types and look up {@code <tenant>:<email>}.
 *
 * <p>The stock authenticators read the typed username and build their forms from the context they are given, so the
 * tenant is applied by wrapping that context rather than by copying their logic.
 */
final class TenantFlowContext {

    /** The attribute the theme reads to show where the person is signing in. */
    static final String TENANT_ATTRIBUTE = "suiteTenant";

    private TenantFlowContext() {}

    static Optional<String> tenant(AuthenticationFlowContext context) {
        return TenantUsername.tenantOf(context.getAuthenticationSession().getClientNote(TenantUsername.TENANT_NOTE));
    }

    /**
     * The form data with the username replaced by the tenant-scoped one; the request's own data stays untouched. A
     * username left empty stays empty, so the stock "enter a username" message still applies.
     */
    static MultivaluedMap<String, String> scoped(Optional<String> tenant, MultivaluedMap<String, String> formData) {
        if (tenant.isEmpty()) {
            return formData;
        }
        String typed = formData.getFirst(AuthenticationManager.FORM_USERNAME);
        if (typed == null || typed.isBlank()) {
            return formData;
        }
        MultivaluedMap<String, String> scoped = new MultivaluedHashMap<>(formData);
        scoped.putSingle(AuthenticationManager.FORM_USERNAME, TenantUsername.compose(tenant.get(), typed));
        return scoped;
    }

    /**
     * A context whose every form already carries the tenant attribute. Each call to {@code form()} builds a new form,
     * and the stock authenticators show their page from several places (first visit, wrong password, locked account),
     * so the one point they all pass through is the context they ask for the form.
     */
    static AuthenticationFlowContext announcing(AuthenticationFlowContext context) {
        Optional<String> tenant = tenant(context);
        if (tenant.isEmpty()) {
            return context;
        }
        String label = TenantUsername.label(tenant.get(), context.getAuthenticationSession().getRedirectUri());
        return proxy(AuthenticationFlowContext.class, context, (method, result) ->
                result instanceof LoginFormsProvider form && "form".equals(method)
                        ? form.setAttribute(TENANT_ATTRIBUTE, label)
                        : result);
    }

    /** A context whose request reads the typed username as the tenant-scoped one. */
    static AuthenticationFlowContext scopingUsername(AuthenticationFlowContext context) {
        Optional<String> tenant = tenant(context);
        if (tenant.isEmpty()) {
            return context;
        }
        return proxy(AuthenticationFlowContext.class, context, (method, result) -> {
            if (result instanceof HttpRequest request && "getHttpRequest".equals(method)) {
                return proxy(HttpRequest.class, request, (requestMethod, requestResult) ->
                        requestResult instanceof MultivaluedMap<?, ?> && "getDecodedFormParameters".equals(requestMethod)
                                ? scoped(tenant, castForm(requestResult))
                                : requestResult);
            }
            return result;
        });
    }

    @SuppressWarnings("unchecked")
    private static MultivaluedMap<String, String> castForm(Object formData) {
        return (MultivaluedMap<String, String>) formData;
    }

    /** What to do with a method's result before the caller sees it. */
    private interface Result {
        Object of(String method, Object result);
    }

    private static <T> T proxy(Class<T> type, T target, Result rewrite) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                return rewrite.of(method.getName(), method.invoke(target, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
