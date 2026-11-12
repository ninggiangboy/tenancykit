package dev.ngb.core.replica.security;

import dev.ngb.core.security.Actor;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** The authenticated suite actor of one request; authorisation uses the actor's permissions, not authorities. */
public class ReplicaAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient Actor actor;

    @SuppressWarnings("this-escape")
    public ReplicaAuthentication(Actor actor) {
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
        return actor.auditName();
    }
}
