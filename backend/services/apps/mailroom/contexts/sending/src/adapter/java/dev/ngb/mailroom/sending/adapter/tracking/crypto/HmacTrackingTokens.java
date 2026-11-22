package dev.ngb.mailroom.sending.adapter.tracking.crypto;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.application.tracking.service.TrackingSettings;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An HMAC-SHA256 token: {@code base64url(version | keyIdLength | keyId | tenantId | emailId | linkId | action | mac)}.
 *
 * <p>It carries no expiry — mail is read years later — and authorises exactly one action on one email. Verification
 * accepts every configured key, so rotating the active key keeps old mail working until the old key is removed. A
 * token that does not verify is an empty result, never an exception.
 */
public class HmacTrackingTokens implements TrackingTokens {

    private static final byte VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final TrackingSettings settings;

    public HmacTrackingTokens(TrackingSettings settings) {
        this.settings = settings;
    }

    @Override
    public String sign(TenantId tenantId, UUID emailId, int linkId, TrackingAction action) {
        byte[] keyId = settings.activeKeyId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + 1 + keyId.length + 16 + 16 + 4 + 1);
        payload.put(VERSION);
        payload.put((byte) keyId.length);
        payload.put(keyId);
        payload.putLong(tenantId.value().getMostSignificantBits());
        payload.putLong(tenantId.value().getLeastSignificantBits());
        payload.putLong(emailId.getMostSignificantBits());
        payload.putLong(emailId.getLeastSignificantBits());
        payload.putInt(linkId);
        payload.put((byte) action.ordinal());
        byte[] body = payload.array();
        byte[] mac = mac(body, settings.activeKey());

        ByteBuffer token = ByteBuffer.allocate(body.length + MAC_LENGTH);
        token.put(body).put(mac);
        return ENCODER.encodeToString(token.array());
    }

    @Override
    public Optional<Claims> verify(String token, TrackingAction expectedAction) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = DECODER.decode(token);
            if (decoded.length < 1 + 1 + MAC_LENGTH) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            if (buffer.get() != VERSION) {
                return Optional.empty();
            }
            int keyIdLength = Byte.toUnsignedInt(buffer.get());
            if (keyIdLength < 1 || buffer.remaining() < keyIdLength + MAC_LENGTH) {
                return Optional.empty();
            }
            byte[] keyIdBytes = new byte[keyIdLength];
            buffer.get(keyIdBytes);
            int bodyLength = decoded.length - MAC_LENGTH;
            byte[] body = new byte[bodyLength];
            System.arraycopy(decoded, 0, body, 0, bodyLength);
            byte[] tag = new byte[MAC_LENGTH];
            System.arraycopy(decoded, bodyLength, tag, 0, MAC_LENGTH);

            String keyId = new String(keyIdBytes, StandardCharsets.UTF_8);
            String key = settings.key(keyId);
            if (key == null || !MessageDigest.isEqual(tag, mac(body, key))) {
                return Optional.empty();
            }

            // Re-read the payload after the shortened header, now that the tag is known good.
            ByteBuffer claims = ByteBuffer.wrap(body, 2 + keyIdLength, bodyLength - (2 + keyIdLength));
            UUID tenantId = new UUID(claims.getLong(), claims.getLong());
            UUID emailId = new UUID(claims.getLong(), claims.getLong());
            int linkId = claims.getInt();
            TrackingAction action = TrackingAction.values()[claims.get()];
            if (action != expectedAction) {
                return Optional.empty();
            }
            return Optional.of(new Claims(new TenantId(tenantId), emailId, linkId));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] mac(byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("the tracking HMAC is unavailable", e);
        }
    }
}
