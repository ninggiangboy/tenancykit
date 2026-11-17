package dev.ngb.workflow.connections.application.credential.port;

/**
 * Envelope encryption for the secrets this context keeps. Decryption accepts an envelope written by any key still in
 * the ring, which is what makes a rotation safe.
 */
public interface SecretCipher {

    String encrypt(String plaintext);

    String decrypt(String envelope);

    String keyId();
}
