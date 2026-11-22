package dev.ngb.platform.access.adapter.auth.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import dev.ngb.core.id.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The provider against a throwaway HTTP server that answers the few Keycloak endpoints it calls. A duplicate username
 * must adopt the identity Keycloak already holds, because the username is deterministic and a retry after a failed
 * compensation has to converge instead of failing forever.
 */
class KeycloakIdentityProviderTest {

    private static final String REALM = "tenancykit";
    private static final TenantId TENANT = new TenantId(UUID.fromString("0198c1a0-0000-7000-8000-000000000042"));

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private boolean userExists;
    private boolean deleteMissing;
    private boolean lookupEmpty;
    private String createdBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/" + REALM + "/protocol/openid-connect/token", this::token);
        server.createContext("/admin/realms/" + REALM + "/users", this::users);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aDuplicateUsernameReturnsTheExistingSubject() {
        userExists = true;

        String subject = provider().createUser("acme:ada@example.test", "ada@example.test", "Ada", "change-me", TENANT);

        assertThat(subject).isEqualTo("kc-existing");
        assertThat(requests).anyMatch(call -> call.startsWith("GET /admin/realms/" + REALM + "/users"));
    }

    @Test
    void aNewUsernameReturnsTheCreatedSubject() {
        userExists = false;

        String subject = provider().createUser("acme:ada@example.test", "ada@example.test", "Ada", "change-me", TENANT);

        assertThat(subject).isEqualTo("kc-created");
    }

    @Test
    void aNewIdentityRecordsTheTenantItBelongsToSoKeycloakCanScopeItsMail() {
        userExists = false;

        provider().createUser("acme:ada@example.test", "ada@example.test", "Ada", "change-me", TENANT);

        assertThat(createdBody).contains("\"attributes\":{\"tenant_id\":[\"" + TENANT.value() + "\"]}");
    }

    @Test
    void deletingAnIdentityThatIsAlreadyGoneIsNotAnError() {
        deleteMissing = true;

        provider().deleteUser("kc-gone");
    }

    @Test
    void aDuplicateUsernameThatCannotBeLookedUpSaysSo() {
        userExists = true;
        lookupEmpty = true;

        assertThatThrownBy(() -> provider().createUser("acme:ada@example.test", "ada@example.test", "Ada", "change-me", TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taken but returns no user");
    }

    private KeycloakIdentityProvider provider() {
        return new KeycloakIdentityProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(), REALM, "platform-admin", "s3cret");
    }

    private void token(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        respond(exchange, 200, "{\"access_token\":\"admin-token\"}");
    }

    private void users(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        if (exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, lookupEmpty ? "[]" : "[{\"id\":\"kc-existing\",\"username\":\"acme:ada@example.test\"}]");
            return;
        }
        if (exchange.getRequestMethod().equals("POST")) {
            createdBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (userExists) {
                respond(exchange, 409, "{\"error\":\"User exists with same username\"}");
                return;
            }
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1/admin/realms/" + REALM + "/users/kc-created");
            respond(exchange, 201, "");
            return;
        }
        if (deleteMissing) {
            respond(exchange, 404, "{\"error\":\"User not found\"}");
            return;
        }
        respond(exchange, 204, "");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
