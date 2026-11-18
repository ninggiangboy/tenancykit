package dev.ngb.core.version;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that accepts an optional {@code If-Match} and answers an {@code ETag}. The API document lists the
 * {@code ETag} response header on a success and a 412 for a stale tag, so a generated client can use both without a
 * hand-written override.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConditionalUpdate {
}
