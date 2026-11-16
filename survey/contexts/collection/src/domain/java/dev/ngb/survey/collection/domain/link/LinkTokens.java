package dev.ngb.survey.collection.domain.link;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A link token: {@code base64url(version | keyIdLength | keyId | linkId | mac)}, signed with HMAC-SHA256.
 *
 * <p>The token carries the link id it names, so a link's URL is rebuilt from storage at any time rather than stored
 * again — the Tenant can read a link back after it was handed out. It carries no tenant: the tenant comes from the
 * verified host, and a link id belonging to another tenant simply does not resolve. Verification accepts every key
 * still listed, so rotating the active key keeps a link already handed out working until its key is removed.
 *
 * <p>A token that does not verify is an empty result, never an exception: every invalid, tampered or foreign token must
 * answer the same 404, so the failure cannot be used to distinguish one cause from another.
 */
public final class LinkTokens {

    /** One key id and its secret, plus which key new tokens carry. */
    public record Keys(String activeKeyId, Map<String, String> keys) {

        public Keys {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("a link token needs at least one configured key");
            }
            if (!keys.containsKey(activeKeyId)) {
                throw new IllegalArgumentException("the active key id names no configured key: " + activeKeyId);
            }
            keys = Map.copyOf(new LinkedHashMap<>(keys));
        }

        String activeSecret() {
            return keys.get(activeKeyId);
        }
    }

    private static final byte VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private LinkTokens() {}

    /** The token for a link id, signed with the active key. */
    public static String sign(UUID linkId, Keys keys) {
        byte[] keyId = keys.activeKeyId().getBytes(StandardCharsets.UTF_8);
        byte[] body = body(keyId, linkId);
        byte[] mac = mac(body, keys.activeSecret());

        ByteBuffer token = ByteBuffer.allocate(body.length + MAC_LENGTH);
        token.put(body).put(mac);
        return ENCODER.encodeToString(token.array());
    }

    /** The link id a token names, or empty when it does not verify against any configured key. */
    public static Optional<UUID> verify(String token, Keys keys) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = DECODER.decode(token);
            if (decoded.length < 1 + 1 + 16 + MAC_LENGTH) {
                return Optional.empty();
            }
            if (decoded[0] != VERSION) {
                return Optional.empty();
            }
            int keyIdLength = Byte.toUnsignedInt(decoded[1]);
            if (keyIdLength < 1 || decoded.length < 2 + keyIdLength + 16 + MAC_LENGTH) {
                return Optional.empty();
            }
            String keyId = new String(decoded, 2, keyIdLength, StandardCharsets.UTF_8);
            String secret = keys.keys().get(keyId);
            if (secret == null) {
                return Optional.empty();
            }
            int bodyLength = decoded.length - MAC_LENGTH;
            byte[] body = new byte[bodyLength];
            System.arraycopy(decoded, 0, body, 0, bodyLength);
            byte[] tag = new byte[MAC_LENGTH];
            System.arraycopy(decoded, bodyLength, tag, 0, MAC_LENGTH);
            if (!MessageDigest.isEqual(tag, mac(body, secret))) {
                return Optional.empty();
            }
            ByteBuffer claims = ByteBuffer.wrap(body, 2 + keyIdLength, bodyLength - (2 + keyIdLength));
            return Optional.of(new UUID(claims.getLong(), claims.getLong()));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] body(byte[] keyId, UUID linkId) {
        ByteBuffer body = ByteBuffer.allocate(1 + 1 + keyId.length + 16);
        body.put(VERSION);
        body.put((byte) keyId.length);
        body.put(keyId);
        body.putLong(linkId.getMostSignificantBits());
        body.putLong(linkId.getLeastSignificantBits());
        return body.array();
    }

    private static byte[] mac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (Exception unavailable) {
            throw new IllegalStateException("the link HMAC is unavailable", unavailable);
        }
    }
}
