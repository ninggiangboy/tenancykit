package dev.ngb.platform.boot.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment configuration of the Platform service. */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(Tokens tokens, Tenancy tenancy, List<ServiceClient> serviceClients, Bootstrap bootstrap) {

    public PlatformProperties {
        serviceClients = serviceClients == null ? List.of() : List.copyOf(serviceClients);
    }

    /**
     * @param issuer the {@code iss} of every token Platform signs
     * @param signingKey the ES256 private key in PEM form; blank generates an ephemeral key for local development
     * @param previousSigningKey the key kept in the JWKS for one token lifetime during rotation
     */
    public record Tokens(String issuer, String signingKey, String previousSigningKey) {}

    /**
     * @param baseDomain the domain a Tenant's subdomain is built from
     * @param platformHostname the hostname Platform Administration answers on
     */
    public record Tenancy(String baseDomain, String platformHostname) {}

    /** The optional first Platform administrator, created through Keycloak on start. */
    public record Bootstrap(String platformAdminEmail, String platformAdminTemporaryPassword) {}

    /**
     * @param secretHash the hex SHA-256 of the client secret; the secret itself is never stored
     * @param scopes the permission codes this client may put in a token
     */
    public record ServiceClient(String id, String secretHash, List<String> scopes) {

        public ServiceClient {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
