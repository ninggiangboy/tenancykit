package dev.ngb.attribution.links.application.link.port;

/** Hashes and verifies a link's password with a slow one-way function, so a stolen store does not reveal it. */
public interface PasswordHasher {

    String hash(String raw);

    boolean matches(String raw, String hash);
}
