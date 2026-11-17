package dev.ngb.booking.gate.domain.token;

import dev.ngb.core.id.TenantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An opaque, HMAC-signed customer token: {@code base64url(version | keyIdLength | keyId | tenant | expiry | offering |
 * scopeMask | refLength | customerRef | mac)}.
 *
 * <p>It is not a JWT: nothing in it is readable by the browser, the signature covers every byte, and the tenant is
 * inside the signed body so a token from another host fails verification here even if its key is shared. Verification
 * accepts every key still listed, so rotating the active key keeps tokens already handed out working.
 */
public final class CustomerTokens {

    /** One key id and its secret, plus which key new tokens carry. */
    public record Keys(String activeKeyId, Map<String, String> keys) {

        public Keys {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("a customer token needs at least one configured key");
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
    private static final UUID NO_OFFERING = new UUID(0, 0);

    private CustomerTokens() {}

    public static String sign(CustomerClaims claims, Keys keys) {
        byte[] keyId = keys.activeKeyId().getBytes(StandardCharsets.UTF_8);
        byte[] body = body(keyId, claims);
        byte[] mac = mac(body, keys.activeSecret());
        ByteBuffer token = ByteBuffer.allocate(body.length + MAC_LENGTH);
        token.put(body).put(mac);
        return ENCODER.encodeToString(token.array());
    }

    /** Empty for every malformed, tampered or foreign-key token; the caller answers one generic 401. */
    public static Optional<CustomerClaims> verify(String token, Keys keys) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = DECODER.decode(token);
            if (decoded.length < 2 + 1 + 16 + 8 + 16 + 1 + 2 + MAC_LENGTH || decoded[0] != VERSION) {
                return Optional.empty();
            }
            int keyIdLength = Byte.toUnsignedInt(decoded[1]);
            if (keyIdLength < 1 || decoded.length < 2 + keyIdLength + 16 + 8 + 16 + 1 + 2 + MAC_LENGTH) {
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
            UUID tenant = new UUID(claims.getLong(), claims.getLong());
            Instant expiresAt = Instant.ofEpochSecond(claims.getLong());
            UUID offering = new UUID(claims.getLong(), claims.getLong());
            Set<GateScope> scopes = scopes(Byte.toUnsignedInt(claims.get()));
            int refLength = Short.toUnsignedInt(claims.getShort());
            if (refLength < 1 || claims.remaining() != refLength) {
                return Optional.empty();
            }
            byte[] ref = new byte[refLength];
            claims.get(ref);
            return Optional.of(new CustomerClaims(
                    new TenantId(tenant),
                    new String(ref, StandardCharsets.UTF_8),
                    scopes,
                    NO_OFFERING.equals(offering) ? null : offering,
                    expiresAt,
                    keyId));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] body(byte[] keyId, CustomerClaims claims) {
        byte[] ref = claims.customerRef().getBytes(StandardCharsets.UTF_8);
        UUID offering = claims.offeringId() == null ? NO_OFFERING : claims.offeringId();
        ByteBuffer body = ByteBuffer.allocate(1 + 1 + keyId.length + 16 + 8 + 16 + 1 + 2 + ref.length);
        body.put(VERSION);
        body.put((byte) keyId.length);
        body.put(keyId);
        body.putLong(claims.tenantId().value().getMostSignificantBits());
        body.putLong(claims.tenantId().value().getLeastSignificantBits());
        body.putLong(claims.expiresAt().getEpochSecond());
        body.putLong(offering.getMostSignificantBits());
        body.putLong(offering.getLeastSignificantBits());
        body.put((byte) mask(claims.scopes()));
        body.putShort((short) ref.length);
        body.put(ref);
        return body.array();
    }

    private static int mask(Set<GateScope> scopes) {
        int mask = 0;
        for (GateScope scope : scopes) {
            mask |= 1 << scope.ordinal();
        }
        return mask;
    }

    private static Set<GateScope> scopes(int mask) {
        Set<GateScope> scopes = EnumSet.noneOf(GateScope.class);
        for (GateScope scope : GateScope.values()) {
            if ((mask & (1 << scope.ordinal())) != 0) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private static byte[] mac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (Exception unavailable) {
            throw new IllegalStateException("the customer token HMAC is unavailable", unavailable);
        }
    }
}
