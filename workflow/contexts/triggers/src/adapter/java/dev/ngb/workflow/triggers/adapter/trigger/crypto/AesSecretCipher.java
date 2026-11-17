package dev.ngb.workflow.triggers.adapter.trigger.crypto;

import dev.ngb.workflow.shared.secret.SecretBox;
import dev.ngb.workflow.triggers.application.trigger.port.SecretCipher;

/** The shared AES-256-GCM box behind the triggers context's port. */
public class AesSecretCipher implements SecretCipher {

    private final SecretBox box;

    public AesSecretCipher(SecretBox box) {
        this.box = box;
    }

    @Override
    public String encrypt(String plaintext) {
        return box.encrypt(plaintext);
    }

    @Override
    public String decrypt(String ciphertext) {
        return box.decrypt(ciphertext);
    }

    @Override
    public String keyId() {
        return box.keyId();
    }
}
