package dev.ngb.workflow.shared.secret;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A set of boxes, one per key id, that reads an envelope written by any key still in the ring.
 *
 * <p>Rotation is adding a box: the new key writes, every old key keeps its values readable, and re-encrypting a value
 * moves it forward. An envelope naming a key that left the ring is refused rather than guessed at.
 */
public final class SecretKeyring {

    private final String activeKeyId;
    private final Map<String, SecretBox> boxes;

    public SecretKeyring(String activeKeyId, Map<String, SecretBox> boxes) {
        this.activeKeyId = activeKeyId;
        this.boxes = new LinkedHashMap<>(boxes);
        if (!this.boxes.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("the active key id must be in the ring");
        }
    }

    /** The id new envelopes carry. */
    public String keyId() {
        return activeKeyId;
    }

    public String encrypt(String plaintext) {
        return boxes.get(activeKeyId).encrypt(plaintext);
    }

    public String decrypt(String envelope) {
        return boxFor(envelope).decrypt(envelope);
    }

    /** Whether an envelope was written by a key older than the active one and should be re-encrypted. */
    public boolean stale(String envelope) {
        return !activeKeyId.equals(keyIdOf(envelope));
    }

    private SecretBox boxFor(String envelope) {
        SecretBox box = boxes.get(keyIdOf(envelope));
        if (box == null) {
            throw new IllegalStateException("the secret envelope names a key this deployment does not hold");
        }
        return box;
    }

    private static String keyIdOf(String envelope) {
        String[] parts = envelope == null ? new String[0] : envelope.split("\\.", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("the secret envelope is malformed");
        }
        return parts[1];
    }

    @Override
    public String toString() {
        return "SecretKeyring[activeKeyId=" + activeKeyId + ", keys=" + boxes.keySet() + "]";
    }
}
