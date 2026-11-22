package dev.ngb.workflow.triggers.application.trigger.port;

/** Envelope encryption of a signing secret, at rest. */
public interface SecretCipher {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);

    String keyId();
}
