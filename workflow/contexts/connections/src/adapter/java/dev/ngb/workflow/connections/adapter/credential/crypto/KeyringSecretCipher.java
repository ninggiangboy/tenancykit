package dev.ngb.workflow.connections.adapter.credential.crypto;

import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.shared.secret.SecretKeyring;

/** The shared key ring behind the connections context's cipher port. */
public class KeyringSecretCipher implements SecretCipher {

    private final SecretKeyring ring;

    public KeyringSecretCipher(SecretKeyring ring) {
        this.ring = ring;
    }

    @Override
    public String encrypt(String plaintext) {
        return ring.encrypt(plaintext);
    }

    @Override
    public String decrypt(String envelope) {
        return ring.decrypt(envelope);
    }

    @Override
    public String keyId() {
        return ring.keyId();
    }
}
