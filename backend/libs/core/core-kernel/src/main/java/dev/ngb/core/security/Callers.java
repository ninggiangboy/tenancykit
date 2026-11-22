package dev.ngb.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares who may call a route. On a controller class it is the default for every handler method; on a method it is
 * an exception that wins over the class. The callers are explicit because the route prefix and the OpenAPI label are
 * derived from them, and a path is never read to decide a caller.
 *
 * <p>Every handler must resolve to exactly one source: this annotation on the method, this annotation on the class,
 * {@link PublicEndpoint}, or an annotation that implies a caller (a portal permission means
 * {@link Caller#CUSTOMER_SESSION}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Callers {

    /** The callers of the route. At least one. */
    Caller[] value();

    /** Whether the caller-derived prefix is added, or the path is served as declared. */
    Prefix prefix() default Prefix.DERIVED;

    /** The two forms of a route's path. */
    enum Prefix {
        /** The route is served under the prefix its callers imply. */
        DERIVED,
        /** The route keeps its declared path because the path is a standard: {@code /oauth/token}, the JWKS. */
        NONE
    }
}
