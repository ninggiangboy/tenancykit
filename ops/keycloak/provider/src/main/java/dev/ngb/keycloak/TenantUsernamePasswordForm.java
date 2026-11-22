package dev.ngb.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.models.KeycloakSession;

/**
 * The browser flow's username and password form for a suite whose usernames are {@code <tenant>:<email>}.
 *
 * <p>When the authorization request names a tenant, the person types an email and this form looks up
 * {@code <tenant>:<email>}; the sign-in page also says which host it is signing in to. Without a tenant (a tool or a
 * direct visit to Keycloak) it behaves exactly like the stock form, so the full username still works.
 */
public class TenantUsernamePasswordForm extends UsernamePasswordForm {

    public TenantUsernamePasswordForm() {}

    public TenantUsernamePasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        super.authenticate(TenantFlowContext.announcing(context));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        super.action(TenantFlowContext.announcing(context));
    }

    @Override
    public boolean validateUserAndPassword(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        return super.validateUserAndPassword(context, TenantFlowContext.scoped(TenantFlowContext.tenant(context), formData));
    }

    @Override
    public boolean validateUser(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        return super.validateUser(context, TenantFlowContext.scoped(TenantFlowContext.tenant(context), formData));
    }
}
