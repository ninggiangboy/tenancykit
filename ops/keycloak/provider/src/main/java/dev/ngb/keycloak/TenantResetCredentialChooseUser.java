package dev.ngb.keycloak;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialChooseUser;

/**
 * The reset-credentials flow's "choose user" step for a suite whose usernames are {@code <tenant>:<email>}.
 *
 * <p>When the authorization request names a tenant, the person types an email and the user looked up is
 * {@code <tenant>:<email>}, so the same email in another tenant is never found from this tenant's host. An email with
 * no account behaves exactly as the stock step does: the flow goes on without a user and the person sees the same
 * "you will receive an email" message, so the page does not say whether an account exists. Without a tenant (a direct
 * visit to Keycloak) it is the stock step and takes the full username.
 */
public class TenantResetCredentialChooseUser extends ResetCredentialChooseUser {

    public static final String PROVIDER_ID = "suite-tenant-reset-choose-user";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        super.authenticate(TenantFlowContext.announcing(context));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        super.action(TenantFlowContext.scopingUsername(TenantFlowContext.announcing(context)));
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Tenant Choose User";
    }

    @Override
    public String getHelpText() {
        return "Chooses the user to reset credentials for; when the request names a tenant, the typed email is looked "
                + "up as <tenant>:<typed email>.";
    }
}
