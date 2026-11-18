package dev.ngb.core.spring.security;

import org.springframework.web.method.HandlerMethod;

/**
 * One check of the permission chain. The interceptor runs the checks in order until one decides the request, so a
 * service plugs in the annotations only it knows - a portal session permission, for example - instead of forking the
 * interceptor. A check that recognises nothing returns false and the next one runs; throwing
 * {@code dev.ngb.core.error.AccessDeniedException} is how a check refuses.
 */
public interface PermissionCheck {

    /** @return true when this check answered for the endpoint, so no later check runs */
    boolean check(HandlerMethod method);
}
