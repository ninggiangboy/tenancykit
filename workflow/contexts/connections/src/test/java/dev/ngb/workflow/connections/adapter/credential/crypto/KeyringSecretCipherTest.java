package dev.ngb.workflow.connections.adapter.credential.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.shared.secret.SecretBox;
import dev.ngb.workflow.shared.secret.SecretKeyring;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A key comes, an old envelope still opens, and only the new key writes. */
class KeyringSecretCipherTest {

    @Test
    void anEnvelopeWrittenBeforeARotationStillDecrypts() {
        SecretBox old = new SecretBox(key("old"), "v1");
        String beforeRotation = old.encrypt("tenant-secret");

        SecretBox current = new SecretBox(key("new"), "v2");
        SecretKeyring ring = new SecretKeyring("v2", Map.of("v1", old, "v2", current));
        KeyringSecretCipher cipher = new KeyringSecretCipher(ring);

        assertThat(cipher.decrypt(beforeRotation)).isEqualTo("tenant-secret");
        assertThat(cipher.keyId()).isEqualTo("v2");
        assertThat(cipher.decrypt(cipher.encrypt("another"))).isEqualTo("another");
        assertThat(cipher.encrypt("another")).startsWith("v1.v2.");
    }

    @Test
    void aKeyThatLeftTheRingIsRefusedInsteadOfGuessedAt() {
        SecretBox gone = new SecretBox(key("gone"), "v0");
        String orphan = gone.encrypt("value");
        SecretKeyring ring = new SecretKeyring("v2", Map.of("v2", new SecretBox(key("new"), "v2")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ring.decrypt(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold");
    }

    private static byte[] key(String seed) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (seed.charAt(index % seed.length()) + index);
        }
        return key;
    }
}
