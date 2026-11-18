package dev.ngb.core.security;

/** Who an actor is. The suite has three: users, tenant systems and service clients of the suite itself. */
public enum ActorType {
    /** A person signed in through Keycloak. */
    USER,
    /** A third party's application calling with a Tenant System credential. */
    TENANT_SYSTEM,
    /** One app of the suite calling another with a service-client token. */
    SERVICE
}
