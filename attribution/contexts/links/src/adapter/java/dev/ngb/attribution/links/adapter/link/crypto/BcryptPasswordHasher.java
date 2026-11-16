package dev.ngb.attribution.links.adapter.link.crypto;

import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt, the encoder the suite already has on its security classpath. Hashing is deliberately slow, so the public unlock
 * path rate-limits an attempt before it reaches here; a hash is never returned, logged or echoed.
 */
public class BcryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public BcryptPasswordHasher(BCryptPasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    @Override
    public boolean matches(String raw, String hash) {
        return raw != null && hash != null && encoder.matches(raw, hash);
    }
}
