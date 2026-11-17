package dev.ngb.booking.gate.domain.queue;

import dev.ngb.core.id.TenantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The ticket token: {@code base64url(version | keyIdLen | keyId | tenant | expiry | ticketNo | offering |
 * targetLen | target | refLen | customerRef | mac)}. It uses the customer-token key set, so one rotation covers both.
 *
 * <p>The token carries the target and the ticket number, which is all the status poll needs; verifying it never touches
 * a ticket row. A forged or foreign-key token is an empty result, so the caller answers one generic refusal.
 */
public final class TicketTokens {

    private static final byte VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TicketTokens() {}

    public static String sign(TicketClaims claims, dev.ngb.booking.gate.domain.token.CustomerTokens.Keys keys) {
        byte[] keyId = keys.activeKeyId().getBytes(StandardCharsets.UTF_8);
        byte[] target = claims.targetId().getBytes(StandardCharsets.UTF_8);
        byte[] ref = claims.customerRef().getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(1 + 1 + keyId.length + 16 + 8 + 8 + 16 + 1 + target.length + 2 + ref.length);
        body.put(VERSION);
        body.put((byte) keyId.length);
        body.put(keyId);
        body.putLong(claims.tenantId().value().getMostSignificantBits());
        body.putLong(claims.tenantId().value().getLeastSignificantBits());
        body.putLong(claims.expiresAt().getEpochSecond());
        body.putLong(claims.ticketNo());
        body.putLong(claims.offeringId().getMostSignificantBits());
        body.putLong(claims.offeringId().getLeastSignificantBits());
        body.put((byte) target.length);
        body.put(target);
        body.putShort((short) ref.length);
        body.put(ref);
        byte[] unsigned = body.array();
        byte[] mac = mac(unsigned, keys.keys().get(keys.activeKeyId()));
        ByteBuffer token = ByteBuffer.allocate(unsigned.length + MAC_LENGTH);
        token.put(unsigned).put(mac);
        return ENCODER.encodeToString(token.array());
    }

    public static Optional<TicketClaims> verify(
            String token, dev.ngb.booking.gate.domain.token.CustomerTokens.Keys keys) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = DECODER.decode(token);
            if (decoded.length < 2 + 1 + 16 + 8 + 8 + 16 + 1 + 2 + MAC_LENGTH || decoded[0] != VERSION) {
                return Optional.empty();
            }
            int keyIdLength = Byte.toUnsignedInt(decoded[1]);
            if (keyIdLength < 1 || decoded.length < 2 + keyIdLength + 16 + 8 + 8 + 16 + 1 + 2 + MAC_LENGTH) {
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
            long ticketNo = claims.getLong();
            UUID offering = new UUID(claims.getLong(), claims.getLong());
            int targetLength = Byte.toUnsignedInt(claims.get());
            if (targetLength < 1 || claims.remaining() < targetLength + 2) {
                return Optional.empty();
            }
            byte[] target = new byte[targetLength];
            claims.get(target);
            int refLength = Short.toUnsignedInt(claims.getShort());
            if (refLength < 1 || claims.remaining() != refLength) {
                return Optional.empty();
            }
            byte[] ref = new byte[refLength];
            claims.get(ref);
            return Optional.of(new TicketClaims(
                    new TenantId(tenant),
                    new String(target, StandardCharsets.UTF_8),
                    offering,
                    ticketNo,
                    new String(ref, StandardCharsets.UTF_8),
                    expiresAt,
                    keyId));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] mac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (Exception unavailable) {
            throw new IllegalStateException("the queue ticket HMAC is unavailable", unavailable);
        }
    }
}
