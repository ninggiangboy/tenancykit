package dev.ngb.attribution.links.application.link.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The digest of a request's meaningful fields, so one idempotency key reused for a different body is caught. */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    /**
     * The hex SHA-256 of {@code parts}; each is followed by a NUL so that {@code ("a","b")} and {@code ("ab","")}
     * cannot collide. A null part reads as empty.
     */
    public static String of(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            canonical.append(part == null ? "" : part).append('\u0000');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
