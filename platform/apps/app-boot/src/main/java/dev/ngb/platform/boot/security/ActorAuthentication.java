package dev.ngb.platform.boot.security;

import dev.ngb.core.security.Actor;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** The authenticated suite actor of one request. */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient Actor actor;

    public ActorAuthentication(Actor actor) {
        super(List.of());
        this.actor = actor;
        setAuthenticated(true);
    }

    public Actor actor() {
        return actor;
    }

    @Override
    public Object getCredentials() {
        return "N/A";
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }
}
