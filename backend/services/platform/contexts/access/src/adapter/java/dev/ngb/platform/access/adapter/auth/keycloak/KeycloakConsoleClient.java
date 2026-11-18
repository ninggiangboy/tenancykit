package dev.ngb.platform.access.adapter.auth.keycloak;

import dev.ngb.platform.access.application.auth.port.ConsoleClientPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Keeps the console client's redirect URIs and web origins in step with the hosts the console is served on.
 *
 * <p>The Admin API replaces the whole client document, so every call reads it, changes only the fields it owns and puts
 * the whole representation back. Two writers can therefore lose one update; that is what the retry and the reconciler
 * are for, and the reconciler is the source of correctness.
 *
 * <p>The post-logout redirect URIs are not a field of Keycloak's client representation: they live in the
 * {@code post.logout.redirect.uris} attribute, a {@code ##} separated list.
 */
public class KeycloakConsoleClient implements ConsoleClientPort {

    private static final int ATTEMPTS = 3;
    private static final String POST_LOGOUT_ATTRIBUTE = "post.logout.redirect.uris";
    private static final String POST_LOGOUT_SEPARATOR = "##";
    /** Keycloak's one entry that allows every origin, instead of expanding one per Tenant host into every token. */
    private static final String ANY_ORIGIN = "*";

    private final KeycloakAdmin admin;
    private final String consoleClientId;
    private final String scheme;
    private final String port;

    public KeycloakConsoleClient(KeycloakAdmin admin, String consoleClientId, String scheme, String port) {
        this.admin = admin;
        this.consoleClientId = consoleClientId;
        this.scheme = scheme;
        this.port = port == null ? "" : port.trim();
    }

    @Override
    public void registerHost(String host) {
        change(client -> {
            Set<String> redirects = new LinkedHashSet<>(strings(client.get("redirectUris")));
            redirects.add(redirect(host));
            client.put("redirectUris", new ArrayList<>(redirects));

            Set<String> loggedOut = new LinkedHashSet<>(postLogoutUris(client));
            loggedOut.add(redirect(host));
            putPostLogout(client, loggedOut);
        });
    }

    @Override
    public void removeHost(String host) {
        change(client -> {
            removeFrom(client, "redirectUris", redirect(host));
            Set<String> loggedOut = new LinkedHashSet<>(postLogoutUris(client));
            if (loggedOut.remove(redirect(host))) {
                putPostLogout(client, loggedOut);
            }
        });
    }

    /**
     * Makes the redirect URIs exactly the hosts that exist, and allows every origin with Keycloak's {@code *}.
     *
     * <p>The console exchanges its authorization code at Keycloak's token endpoint from the page's own origin, so the
     * public client needs a cross-origin allowance. It must not be one entry per Tenant host: Keycloak writes the web
     * origins into every access token as {@code allowed-origins}, so the list would grow every person's token with
     * every Tenant the suite ever creates. The single {@code *} keeps the token small; the redirect URIs, which are
     * still exactly the hosts that exist, are what decides where a code may go.
     */
    @Override
    public void syncHosts(Set<String> hosts) {
        change(client -> {
            List<String> redirects = hosts.stream().sorted().map(this::redirect).toList();
            client.put("redirectUris", new ArrayList<>(redirects));
            putPostLogout(client, new LinkedHashSet<>(redirects));
            client.put("webOrigins", new ArrayList<>(List.of(ANY_ORIGIN)));
        });
    }

    private void change(java.util.function.Consumer<Map<String, Object>> mutation) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Map<String, Object> client = admin.fetchClient(consoleClientId);
            mutation.accept(client);
            try {
                admin.http()
                        .put()
                        .uri("/admin/realms/{realm}/clients/{id}", admin.realm(), client.get("id"))
                        .header("Authorization", "Bearer " + admin.token())
                        .body(client)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (HttpClientErrorException e) {
                // A concurrent writer replaced the client between the read and the write; read it again.
                if (e.getStatusCode().value() != 409 || attempt == ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private List<String> postLogoutUris(Map<String, Object> client) {
        Object value = attributes(client).get(POST_LOGOUT_ATTRIBUTE);
        if (value instanceof String list && !list.isBlank()) {
            return List.of(list.split(POST_LOGOUT_SEPARATOR));
        }
        return List.of();
    }

    private void putPostLogout(Map<String, Object> client, Set<String> uris) {
        attributes(client).put(POST_LOGOUT_ATTRIBUTE, String.join(POST_LOGOUT_SEPARATOR, uris));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> client) {
        Object value = client.get("attributes");
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        client.put("attributes", created);
        return created;
    }

    private String redirect(String host) {
        return scheme + "://" + publishedHost(host) + "/console/*";
    }

    /**
     * The console is published on one port, which a hostname recorded without one does not carry. Both the reconciler
     * and a domain add therefore go through here, so a host registered by either is the same redirect target.
     */
    private String publishedHost(String host) {
        return port.isEmpty() || host.contains(":") ? host : host + ":" + port;
    }

    private static void removeFrom(Map<String, Object> client, String field, String value) {
        Set<String> values = new LinkedHashSet<>(strings(client.get(field)));
        if (values.remove(value)) {
            client.put(field, new ArrayList<>(values));
        }
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            List<String> strings = new ArrayList<>(list.size());
            for (Object entry : list) {
                if (entry instanceof String text) {
                    strings.add(text);
                }
            }
            return strings;
        }
        return List.of();
    }
}
