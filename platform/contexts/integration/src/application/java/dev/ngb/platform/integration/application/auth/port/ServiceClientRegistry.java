package dev.ngb.platform.integration.application.auth.port;

import java.util.List;
import java.util.Optional;

/** The service clients of the suite, configured deployment-side. A service client is not a Tenant System. */
public interface ServiceClientRegistry {

    /** @param scopes the permission codes this client may put in a token */
    record Client(String id, List<String> scopes) {

        public Client {
            scopes = List.copyOf(scopes);
        }
    }

    /** @return the client when the id and secret match, empty for anything unknown or wrong */
    Optional<Client> authenticate(String id, String secret);
}
