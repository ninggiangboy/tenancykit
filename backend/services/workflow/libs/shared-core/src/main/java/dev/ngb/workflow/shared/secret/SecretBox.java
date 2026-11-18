package dev.ngb.workflow.shared.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption for a secret this app must keep: AES-256-GCM with a random nonce per value and a key id stored
 * beside the ciphertext, so a key can rotate while old values stay readable.
 *
 * <p>The envelope is {@code v1.<keyId>.<base64(nonce || ciphertext)>}. The nonce is 12 bytes and the tag is GCM's 128
 * bits; a tampered envelope fails to decrypt rather than returning bytes. A secret value never appears in
 * {@link #toString()}, and a caller never logs the plaintext.
 */
public final class SecretBox {

    private static final String VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final String keyId;
    private final SecretKeySpec key;

    public SecretBox(byte[] keyBytes, String keyId) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("a secret key is exactly 32 bytes for AES-256");
        }
        if (keyId == null || keyId.isBlank() || keyId.contains(".")) {
            throw new IllegalArgumentException("a key id is non-empty and holds no dot");
        }
        this.keyId = keyId;
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** The id this box encrypts under, stored with every envelope it writes. */
    public String keyId() {
        return keyId;
    }

    public String encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return VERSION + "." + keyId + "." + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a secret", e);
        }
    }

    public String decrypt(String envelope) {
        String[] parts = envelope == null ? new String[0] : envelope.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || !keyId.equals(parts[1])) {
            throw new IllegalStateException("the secret envelope does not belong to this key");
        }
        byte[] bytes = Base64.getDecoder().decode(parts[2]);
        if (bytes.length <= NONCE_BYTES) {
            throw new IllegalStateException("the secret envelope is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            byte[] plaintext = cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the secret envelope could not be decrypted", e);
        }
    }

    /** Constant-time comparison of two secrets, so a signature check leaks no prefix. */
    public static boolean sameSecret(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "SecretBox[keyId=" + keyId + "]";
    }
}
