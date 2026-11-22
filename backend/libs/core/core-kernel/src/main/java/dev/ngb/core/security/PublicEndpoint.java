package dev.ngb.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that is reachable without a signed-in actor because its caller proves itself another way, for
 * example a signed webhook. The endpoint must verify that proof before it acts.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

    /** Who calls it: {@link Caller#PUBLIC} for an anonymous browser, {@link Caller#PROVIDER} for a signed webhook. */
    Caller caller() default Caller.PUBLIC;
}
