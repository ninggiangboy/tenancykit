package dev.ngb.keycloak;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.KeycloakSession;

/** Registers {@link TenantUsernamePasswordForm}; everything else about the stock form's registration is kept. */
public class TenantUsernamePasswordFormFactory extends UsernamePasswordFormFactory {

    public static final String PROVIDER_ID = "suite-tenant-username-password-form";

    @Override
    public Authenticator create(KeycloakSession session) {
        return new TenantUsernamePasswordForm(session);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Tenant Username Password Form";
    }

    @Override
    public String getHelpText() {
        return "Validates a username and password; when the request names a tenant, the username is looked up as "
                + "<tenant>:<typed username>.";
    }
}
