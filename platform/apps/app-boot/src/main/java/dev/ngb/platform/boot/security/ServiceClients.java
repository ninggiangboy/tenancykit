package dev.ngb.platform.boot.security;

import dev.ngb.platform.boot.config.PlatformProperties.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The service clients of the suite, configured deployment-side and compared in constant time. */
public class ServiceClients {

    private final Map<String, ServiceClient> byId;

    public ServiceClients(List<ServiceClient> clients) {
        Map<String, ServiceClient> configured = new LinkedHashMap<>();
        clients.forEach(client -> configured.put(client.id(), client));
        this.byId = Map.copyOf(configured);
    }

    public Optional<ServiceClient> authenticate(String id, String secret) {
        ServiceClient client = byId.get(id);
        if (client == null || secret == null || client.secretHash() == null) {
            return Optional.empty();
        }
        byte[] presented = sha256(secret).getBytes(StandardCharsets.UTF_8);
        byte[] stored = client.secretHash().getBytes(StandardCharsets.UTF_8);
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
