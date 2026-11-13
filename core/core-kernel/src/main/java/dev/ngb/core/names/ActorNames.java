package dev.ngb.core.names;

import dev.ngb.core.security.ActorType;
import java.util.Locale;

/**
 * The names an actor is recorded and addressed under. The separator is {@code /}: an actor name is
 * {@code <kind>/<id>}, such as {@code user/<uuid>} or {@code system/stripe}, and the same shape is the subject of
 * the token the issuer signs.
 *
 * <p>A name is stored in ledger entries, audit entries and tokens, so it must not change once released. Only
 * {@code auditName} and the subject prefixes are built here; a service keeps its own {@code ...Actors} constants
 * for the names it acts under.
 */
public final class ActorNames {

    /** The separator between the kind and the id of an actor name, and inside a composed target. */
    public static final String SEPARATOR = "/";

    /** A person signed in through Keycloak. */
    public static final String USER = "user";

    /** A third party's application calling with a Tenant System credential. */
    public static final String TENANT_SYSTEM = "tenant-system";

    /** One app of the suite calling another with a service-client token. */
    public static final String SERVICE = "service";

    /** The suite itself, acting without a person behind the change. */
    public static final String SYSTEM = "system";

    /** The subject prefix Platform uses for a Tenant System token. */
    public static final String TENANT_SYSTEM_SUBJECT = TENANT_SYSTEM + SEPARATOR;

    /** The subject prefix Platform uses for a service-client token. */
    public static final String SERVICE_SUBJECT = SERVICE + SEPARATOR;

    private ActorNames() {}

    /** {@code <kind>/<id>}, the shape every actor name has. */
    public static String of(String kind, Object id) {
        return kind + SEPARATOR + id;
    }

    public static String user(Object id) {
        return of(USER, id);
    }

    public static String tenantSystem(Object id) {
        return of(TENANT_SYSTEM, id);
    }

    public static String service(Object id) {
        return of(SERVICE, id);
    }

    /** {@code system/<name>}, such as {@code system/stripe} or {@code system/ledger-verify}. */
    public static String system(String name) {
        return of(SYSTEM, name);
    }

    /** The name an actor of {@code type} is recorded under. */
    public static String of(ActorType type, Object id) {
        return of(type.name().toLowerCase(Locale.ROOT).replace('_', '-'), id);
    }
}
