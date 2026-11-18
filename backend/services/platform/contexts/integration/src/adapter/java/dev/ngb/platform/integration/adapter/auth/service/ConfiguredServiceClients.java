package dev.ngb.platform.integration.adapter.auth.service;

import dev.ngb.platform.integration.application.auth.port.ServiceClientRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The service clients of the suite, configured deployment-side and compared in constant time. */
public class ConfiguredServiceClients implements ServiceClientRegistry {

    /** The configuration shape: id, hex SHA-256 of the secret and the scopes it may put in a token. */
    public record Configured(String id, String secretHash, List<String> scopes) {

        public Configured {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    private final Map<String, Client> byId;
    private final Map<String, String> secretHashes;

    public ConfiguredServiceClients(List<Configured> clients) {
        Map<String, Client> configured = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Configured client : clients) {
            configured.put(client.id(), new Client(client.id(), client.scopes()));
            hashes.put(client.id(), client.secretHash());
        }
        this.byId = Map.copyOf(configured);
        this.secretHashes = Map.copyOf(hashes);
    }

    @Override
    public Optional<Client> authenticate(String id, String secret) {
        Client client = byId.get(id);
        String expected = secretHashes.get(id);
        if (client == null || secret == null || expected == null) {
            return Optional.empty();
        }
        byte[] presented = sha256(secret).getBytes(StandardCharsets.UTF_8);
        byte[] stored = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, stored) ? Optional.of(client) : Optional.empty();
    }

    /** The hex SHA-256 of a secret, the form configuration holds. */
    public static String sha256(String secret) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
