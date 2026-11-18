package dev.ngb.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission code an endpoint needs, namespaced by the enforcing app such as
 * {@code platform:tenant:create}. A request handler with neither this nor {@link Authenticated} is rejected at
 * runtime, so no endpoint is open by accident.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    String value();
}
