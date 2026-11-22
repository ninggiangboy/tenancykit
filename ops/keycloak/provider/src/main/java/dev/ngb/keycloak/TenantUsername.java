package dev.ngb.keycloak;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rule that turns what a person types into the username Keycloak stores.
 *
 * <p>Platform creates every identity as {@code <tenant-code>:<email>} (the reserved code {@code platform} for Platform
 * users), because usernames are unique in the shared realm while an email is not. The console knows the tenant from
 * its host and sends it with the authorization request, so the person types only the email.
 */
final class TenantUsername {

    /** The note Keycloak keeps for the authorization request parameter {@code tenant}. */
    static final String TENANT_NOTE = "client_request_param_tenant";

    /** The tenant code format Platform enforces in {@code TenantCode}; anything else is not a tenant. */
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]$");

    private TenantUsername() {}

    /** The tenant a request named, when it names a well-formed code. A malformed value is treated as no tenant. */
    static Optional<String> tenantOf(String requested) {
        return Optional.ofNullable(requested).map(String::trim).filter(code -> CODE.matcher(code).matches());
    }

    /**
     * The username to look up for what was typed in a tenant's sign-in. The tenant is always prepended, so a typed
     * {@code other:email} becomes {@code tenant:other:email} and finds no one: a person cannot sign in to another
     * tenant's identity from this tenant's host.
     */
    static String compose(String tenant, String typed) {
        return tenant + ":" + (typed == null ? "" : typed.trim());
    }

    /** What to tell the person about where they are signing in: the host they came from, else the tenant code. */
    static String label(String tenant, String redirectUri) {
        if (redirectUri != null) {
            try {
                String host = URI.create(redirectUri).getHost();
                if (host != null && !host.isBlank()) {
                    return host;
                }
            } catch (IllegalArgumentException ignored) {
                // A redirect URI Keycloak accepted is well-formed; if it is not, the tenant code is still a fair label.
            }
        }
        return tenant;
    }
}
