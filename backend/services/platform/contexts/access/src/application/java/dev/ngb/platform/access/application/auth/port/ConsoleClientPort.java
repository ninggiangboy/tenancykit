package dev.ngb.platform.access.application.auth.port;

import java.util.Set;

/**
 * Keycloak trusts the redirect targets the console signs in through, and the console runs on every tenant host. This is
 * the one place that keeps the client's redirect URIs in step with the hosts that exist.
 *
 * <p>The host is a plain hostname, sometimes with a port for local development; the scheme is deployment configuration.
 */
public interface ConsoleClientPort {

    /** Adds the host's console redirect URI and web origin; adding a host that is already there changes nothing. */
    void registerHost(String host);

    /** Removes the host's console redirect URI and web origin; a host that is not there changes nothing. */
    void removeHost(String host);

    /**
     * Makes the client accept exactly these hosts, which is what the reconciler calls: idempotent, and safe to repeat
     * on every instance.
     */
    void syncHosts(Set<String> hosts);
}
