package dev.ngb.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A route that returns only the caller's own data and therefore has no permission code: both person kinds may call it,
 * and it is mounted once at {@link CallerPrefixes#ME} rather than under the two prefixes each kind implies.
 *
 * <p>A handler declares {@code @RequestMapping("/notifications")} and is served at {@code /api/v1/me/notifications}.
 * The caller label still names both kinds, so a reader sees who may call it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Callers({Caller.PLATFORM_USER, Caller.TENANT_USER})
public @interface SelfService {}
