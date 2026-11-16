package dev.ngb.survey.collection.domain.visit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * The opaque token a visitor holds for one visit, and the hash the database keeps.
 *
 * <p>The token is 32 random bytes in base64url, which is unguessable and carries nothing: it authorises the endpoints a
 * respondent's browser calls and nothing else. Only its SHA-256 reaches storage, so a copy of the database cannot be
 * replayed against the public API.
 */
public final class VisitToken {

    /** Bytes of entropy in one token: 256 bits, which no client can enumerate. */
    private static final int BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private VisitToken() {}

    /** A fresh token, and the hash to store beside it. */
    public record Issued(String token, byte[] hash) {}

    public static Issued issue() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);
        return new Issued(token, hash(token));
    }

    /**
     * The SHA-256 an incoming token is looked up by.
     *
     * @return the hash, or empty for a blank or non-base64url token, which is what a malformed one must answer as
     */
    public static Optional<byte[]> hashOfPresented(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(hash(token));
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
