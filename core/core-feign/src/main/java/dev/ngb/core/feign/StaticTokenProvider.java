package dev.ngb.core.feign;

import java.util.Objects;

/** A token that is already known, such as an administrator's bearer token for a one-shot bootstrap. */
public final class StaticTokenProvider implements TokenProvider {

    private final String token;

    public StaticTokenProvider(String token) {
        this.token = token == null ? "" : token;
    }

    @Override
    public String token() {
        if (token.isBlank()) {
            throw new IllegalStateException("no token was configured");
        }
        return token;
    }

    @Override
    public boolean configured() {
        return !token.isBlank();
    }

    @Override
    public boolean invalidate() {
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StaticTokenProvider provider && Objects.equals(token, provider.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }
}
