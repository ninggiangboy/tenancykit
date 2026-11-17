package dev.ngb.workflow.connections.domain.connection;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A Tenant-owned way to call one external system: the host it may reach, the headers it always sends, and a secret
 * kept as a ciphertext envelope.
 *
 * <p>The plaintext secret never lives in this record. It is decrypted at call time and the result is never logged, so
 * {@code toString} shows the envelope's key id and nothing more.
 */
public record Connection(
        UUID id,
        TenantId tenantId,
        String name,
        ConnectionKind kind,
        String allowedHost,
        String secretCiphertext,
        String secretKeyId,
        Map<String, String> headers,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Connection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(allowedHost, "allowedHost");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** Whether a host is the one this connection allows, or under it when the pattern starts with a dot. */
    public boolean allows(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String checked = host.toLowerCase();
        String pattern = allowedHost.toLowerCase();
        if (pattern.startsWith(".")) {
            return checked.endsWith(pattern) || checked.equals(pattern.substring(1));
        }
        return checked.equals(pattern);
    }

    public boolean hasSecret() {
        return secretCiphertext != null;
    }
}
