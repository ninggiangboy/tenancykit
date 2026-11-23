package dev.ngb.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The route prefix a caller implies. A controller declares only its resource path and the framework mounts it under
 * one mapping per prefix that its callers imply; the prefix is never read back to decide who may call a route.
 *
 * <p>The public callers are mounted under {@code /public/<app code>} only once an app has moved its public controllers
 * there. Until then they keep the path they declare, so the same argument list yields no prefix for them.
 */
public final class CallerPrefixes {

    /** The prefix of a Tenant User's route. */
    public static final String API = "/api/v1";

    /** The prefix of a Tenant System's route, the machine-twin of the same handler. */
    public static final String SYSTEM = "/system/v1";

    /** The prefix of a Platform User's route. */
    public static final String ADMIN = "/admin/v1";

    /** The prefix of another service's route; never routed at the edge. */
    public static final String INTERNAL = "/internal/v1";

    /** The one path both person kinds share because it carries only their own data. */
    public static final String ME = "/api/v1/me";

    /** The root of an app's public namespace. */
    public static final String PUBLIC = "/public";

    private CallerPrefixes() {}

    /** The prefix one caller implies for an app. */
    public static String of(Caller caller, String appCode) {
        return switch (caller) {
            case TENANT_USER -> API;
            case TENANT_SYSTEM -> SYSTEM;
            case PLATFORM_USER -> ADMIN;
            case INTERNAL -> INTERNAL;
            case PUBLIC, PROVIDER, CUSTOMER_SESSION -> PUBLIC + "/" + appCode;
        };
    }

    /** Whether a caller's prefix lives in the app's public namespace, which each app moves in its own phase. */
    public static boolean isInPublicNamespace(Caller caller) {
        return caller == Caller.PUBLIC || caller == Caller.PROVIDER || caller == Caller.CUSTOMER_SESSION;
    }

    /**
     * The prefixes to mount, one per distinct prefix, in declaration order.
     *
     * @param callers the callers the handler declares
     * @param appCode this app's code, the app segment of the public namespace
     * @param publicNamespace whether the app's public controllers already live under {@code /public/<app code>}
     */
    public static List<String> of(Set<Caller> callers, String appCode, boolean publicNamespace) {
        List<String> prefixes = new ArrayList<>();
        for (Caller caller : Caller.values()) {
            if (!callers.contains(caller) || (isInPublicNamespace(caller) && !publicNamespace)) {
                continue;
            }
            String prefix = of(caller, appCode);
            if (!prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }
}
