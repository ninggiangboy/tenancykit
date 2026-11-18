package dev.ngb.core.visitor;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The anonymous daily visitor hash: {@code HMAC-SHA256(dayKey, tenantId ‖ ip ‖ userAgent)} where
 * {@code dayKey = HMAC(masterKey, yyyy-MM-dd)}.
 *
 * <p>Two properties matter. The hash differs on every UTC day, so a hash alone cannot link one person across days, and
 * because the day key is itself derived from the master key a leaked hash does not reveal the address. The output is
 * 16 bytes, base64url without padding.
 *
 * <p>The address and user agent are used in memory and never returned or logged; only the hash leaves this class.
 */
public final class DailyVisitorHash {

    /** The stored form: 16 bytes is 22 base64url characters, and a column needs room for no more. */
    public static final int LENGTH = 22;

    private static final int BYTES = 16;

    private final byte[] masterKey;

    /**
     * @param masterKey the deployment's {@code ATTRIBUTION_VISITOR_HASH_KEY}; a blank key is refused by the app's
     *     configuration, not here
     */
    public DailyVisitorHash(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("the visitor hash key must not be blank");
        }
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The hash of one visit on one UTC day.
     *
     * @param tenantId the tenant the visit belongs to, so two tenants' identical visitors never share a hash
     * @param ip the client address, used only here
     * @param userAgent the client's user agent, used only here
     * @param day the UTC day the visit is counted in
     */
    public String of(String tenantId, String ip, String userAgent, LocalDate day) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("a visitor hash needs a tenant");
        }
        byte[] dayKey = hmac(masterKey, day.toString().getBytes(StandardCharsets.UTF_8));
        Mac mac = mac(dayKey);
        mac.update((byte) 0);
        mac.update(tenantId.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        mac.update((ip == null ? "" : ip).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        mac.update((userAgent == null ? "" : userAgent).getBytes(StandardCharsets.UTF_8));
        byte[] full = mac.doFinal();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(full, BYTES));
    }

    /** The same hash for an instant, using its UTC day. */
    public String of(String tenantId, String ip, String userAgent, java.time.Instant at) {
        return of(tenantId, ip, userAgent, at.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        Mac mac = mac(key);
        return mac.doFinal(message);
    }

    private static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
