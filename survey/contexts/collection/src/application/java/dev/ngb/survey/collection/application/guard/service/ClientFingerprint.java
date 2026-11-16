package dev.ngb.survey.collection.application.guard.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The opaque fingerprint a rate-limit bucket and a log line may use instead of an address.
 *
 * <p>It is {@code HMAC-SHA256(dailyKey, ip)} truncated and base64url-encoded, where the daily key is derived from the
 * app's master key and the UTC date. Two properties matter: a fingerprint cannot be reversed into an address, and it
 * changes every day, so one caller's traffic cannot be followed across days even by someone holding the whole map.
 */
public final class ClientFingerprint {

    private static final int BYTES = 12;

    private final byte[] masterKey;

    public ClientFingerprint(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("the fingerprint key must not be blank");
        }
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
    }

    /** The fingerprint of one address on one day. */
    public String of(String address, LocalDate day) {
        try {
            Mac dayKey = Mac.getInstance("HmacSHA256");
            dayKey.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            byte[] derived = dayKey.doFinal(day.toString().getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            byte[] digest = mac.doFinal(String.valueOf(address).getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[BYTES];
            System.arraycopy(digest, 0, truncated, 0, BYTES);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (Exception unavailable) {
            throw new IllegalStateException("the fingerprint HMAC is unavailable", unavailable);
        }
    }

    /** The fingerprint of one address today, which is what a request handler asks for. */
    public String today(String address) {
        return of(address, LocalDate.now(ZoneOffset.UTC));
    }

    /** Whether two fingerprints are the same, in constant time, for a caller that compares them. */
    public static boolean same(String left, String right) {
        return MessageDigest.isEqual(
                String.valueOf(left).getBytes(StandardCharsets.UTF_8),
                String.valueOf(right).getBytes(StandardCharsets.UTF_8));
    }
}
