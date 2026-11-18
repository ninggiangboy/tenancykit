package dev.ngb.workflow.runtime.domain.step;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The key one step sends under, stable across retries and restarts: a digest of the instance, the token, the node and
 * the iteration. A target that honours {@code Idempotency-Key} shows one effect for a repeated send.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    public static String of(UUID instanceId, UUID tokenId, String nodeId, int iteration) {
        String material = instanceId + ":" + tokenId + ":" + nodeId + ":" + iteration;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
