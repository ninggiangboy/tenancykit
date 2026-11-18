package dev.ngb.platform.access.adapter.auth.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console client against a throwaway Keycloak: registering a host adds its redirect URI and web origin, syncing
 * makes the set exactly the hosts that exist while keeping Keycloak's {@code +} wildcard, and a conflicting write is
 * retried against the representation the other writer left.
 */
class KeycloakConsoleClientTest {

    private static final String REALM = "billing";
    private static final String CLIENT_ID = "suite-console";

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private Map<String, Object> client;
    private int conflictsLeft;

    @BeforeEach
    void start() throws IOException {
        client = new LinkedHashMap<>();
        client.put("id", "client-1");
        client.put("clientId", CLIENT_ID);
        client.put("redirectUris", new ArrayList<>(List.of("http://old.test/console/*")));
        client.put("webOrigins", new ArrayList<>(List.of("*")));
        client.put("postLogoutRedirectUris", new ArrayList<>(List.of("http://old.test/console/*")));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/" + REALM + "/protocol/openid-connect/token", this::token);
        server.createContext("/admin/realms/" + REALM + "/clients", this::clients);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void registeringAHostAddsItsRedirectUriAndOriginAndKeepsTheOthers() {
        client().registerHost("acme.localhost");

        assertThat(strings(client.get("redirectUris")))
                .contains("http://old.test/console/*", "http://acme.localhost/console/*");
        assertThat(strings(client.get("webOrigins"))).containsExactly("*");
        assertThat(postLogoutUris()).contains("http://acme.localhost/console/*");
    }

    @Test
    void registeringTheSameHostTwiceChangesNothing() {
        client().registerHost("acme.localhost");
        List<String> afterFirst = strings(client.get("redirectUris"));
        int writes = requests.stream().filter(call -> call.startsWith("PUT ")).toList().size();

        client().registerHost("acme.localhost");

        assertThat(strings(client.get("redirectUris"))).isEqualTo(afterFirst);
        assertThat(requests.stream().filter(call -> call.startsWith("PUT ")).toList()).hasSize(writes + 1);
    }

    @Test
    void removingAHostRemovesOnlyItsEntries() {
        client().registerHost("acme.localhost");

        client().removeHost("acme.localhost");

        assertThat(strings(client.get("redirectUris"))).doesNotContain("http://acme.localhost/console/*");
        assertThat(strings(client.get("webOrigins"))).containsExactly("*");
        assertThat(strings(client.get("redirectUris"))).contains("http://old.test/console/*");
    }

    @Test
    void syncingMakesTheSetExactlyTheHostsAndKeepsTheWildcardOrigin() {
        client().syncHosts(Set.of("platform.localhost", "acme.localhost"));

        assertThat(strings(client.get("redirectUris"))).containsExactly(
                "http://acme.localhost/console/*", "http://platform.localhost/console/*");
        assertThat(postLogoutUris()).containsExactly(
                "http://acme.localhost/console/*", "http://platform.localhost/console/*");
        assertThat(strings(client.get("webOrigins"))).containsExactly("*");
    }

    @Test
    void aPublishedPortIsAppliedToAHostWithoutOneAndNeverTwice() {
        client("8090").registerHost("acme.localhost");
        client("8090").registerHost("other.localhost:9000");

        assertThat(strings(client.get("redirectUris")))
                .contains("http://acme.localhost:8090/console/*", "http://other.localhost:9000/console/*");
        assertThat(strings(client.get("webOrigins"))).containsExactly("*");
    }

    @Test
    void syncingReplacesAPerHostOriginListWithTheOneWildcard() {
        client.put("webOrigins", new ArrayList<>(List.of("+", "http://acme.localhost:8090")));

        client().syncHosts(Set.of("acme.localhost"));

        assertThat(strings(client.get("webOrigins"))).containsExactly("*");
    }

    @Test
    void aConflictingWriteIsRetried() {
        conflictsLeft = 1;

        client().registerHost("acme.localhost");

        assertThat(strings(client.get("redirectUris"))).contains("http://acme.localhost/console/*");
        assertThat(requests.stream().filter(call -> call.startsWith("PUT ")).toList()).hasSize(2);
    }

    private List<String> postLogoutUris() {
        Object attributes = client.get("attributes");
        if (attributes instanceof Map<?, ?> map) {
            Object value = map.get("post.logout.redirect.uris");
            if (value instanceof String list && !list.isBlank()) {
                return List.of(list.split("##"));
            }
        }
        return List.of();
    }

    private KeycloakConsoleClient client() {
        return client("");
    }

    private KeycloakConsoleClient client(String port) {
        return new KeycloakConsoleClient(
                new KeycloakAdmin("http://127.0.0.1:" + server.getAddress().getPort(), REALM, "platform-admin", "secret"),
                CLIENT_ID,
                "http",
                port);
    }

    private void token(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        Map<String, Object> body = Map.of("access_token", "admin-token", "expires_in", 300);
        respond(exchange, 200, json.writeValueAsBytes(body));
    }

    @SuppressWarnings("unchecked")
    private void clients(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        if (exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, json.writeValueAsBytes(List.of(client)));
            return;
        }
        if (exchange.getRequestMethod().equals("PUT")) {
            if (conflictsLeft > 0) {
                conflictsLeft--;
                respond(exchange, 409, new byte[0]);
                return;
            }
            try (InputStream in = exchange.getRequestBody()) {
                JsonNode updated = json.readTree(in.readAllBytes());
                client = new LinkedHashMap<>(json.convertValue(updated, Map.class));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        respond(exchange, 405, new byte[0]);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String text) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
