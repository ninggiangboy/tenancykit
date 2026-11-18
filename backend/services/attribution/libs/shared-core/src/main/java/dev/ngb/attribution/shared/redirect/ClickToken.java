package dev.ngb.attribution.shared.redirect;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed click id the redirect appends to a destination: {@code base64url(click_id ‖ key_id ‖ HMAC-SHA256(secret,
 * click_id ‖ key_id))}.
 *
 * <p>The signature is what makes the collection endpoints and the postback trustworthy: a caller can only name a click
 * this app issued, because a token it made up carries a signature it cannot produce. {@link #verify} is a pure function
 * with no state and no clock, so the page-view endpoint and the postback verify with the same code the redirect signed
 * with, and a tampered token fails everywhere at once.
 *
 * <p>The key id travels inside the token so a rotation keeps links already in the wild valid: {@link #verify} looks the
 * secret up by the id the token names and accepts any key the deployment still lists.
 */
public final class ClickToken {

    /** The largest token a link may carry: a click id, a key id and a signature are far shorter than this. */
    public static final int MAX_LENGTH = 512;

    private static final char SEPARATOR = '~';
    private static final int SIGNATURE_BYTES = 32;
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private ClickToken() {}

    /**
     * Sign a freshly created click id.
     *
     * @param clickId the click's identity, generated on the redirect path
     * @param keyId the id of the secret that signs it, so a rotation can still verify the links it signed
     * @param secret the HMAC key that key id names
     */
    public static String sign(UUID clickId, String keyId, String secret) {
        if (clickId == null) {
            throw new IllegalArgumentException("a click token needs a click id");
        }
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("a click token needs a key id");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("a click token needs a signing secret");
        }
        // Both readable parts travel base64url too, so the token is one opaque segment a URL parser cannot split.
        String body = ENCODER.encodeToString(clickId.toString().getBytes(StandardCharsets.UTF_8))
                + SEPARATOR + ENCODER.encodeToString(keyId.getBytes(StandardCharsets.UTF_8));
        return body + SEPARATOR + ENCODER.encodeToString(hmac(secret, body));
    }

    /**
     * A token verified down to the click it names.
     *
     * @param clickId the click the token proves
     * @param keyId the key id the token carries, so a caller can tell which secret signed it
     */
    public record Verified(UUID clickId, String keyId) {}

    /**
     * Verify a token and return the click it names, or empty when the token is malformed, names a key id this deployment
     * does not hold, or carries a signature that does not match.
     *
     * @param token the value from the destination's query, or any value a caller sent
     * @param secrets the signing secrets still accepted, keyed by key id
     */
    public static Optional<Verified> verify(String token, Map<String, String> secrets) {
        if (token == null || token.isBlank() || token.length() > MAX_LENGTH || secrets == null) {
            return Optional.empty();
        }
        String[] parts = token.split(String.valueOf(SEPARATOR), -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        String keyId;
        UUID clickId;
        byte[] presented;
        try {
            keyId = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            clickId = UUID.fromString(new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8));
            presented = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String secret = secrets.get(keyId);
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        byte[] expected = hmac(secret, parts[0] + SEPARATOR + parts[1]);
        // Constant-time comparison, so a token cannot be recovered one byte at a time from the response time.
        if (!MessageDigest.isEqual(expected, presented)) {
            return Optional.empty();
        }
        return Optional.of(new Verified(clickId, keyId));
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)), SIGNATURE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
