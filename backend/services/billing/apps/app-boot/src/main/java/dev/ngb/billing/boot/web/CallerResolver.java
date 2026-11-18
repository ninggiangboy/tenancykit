package dev.ngb.billing.boot.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Builds the {@link Audit} recorded in audit entries from the authenticated actor and the request id. */
public class CallerResolver {

    private final CurrentActor currentActor;

    public CallerResolver(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    public Audit current() {
        return Audit.of(currentActor.get().auditName(), suppliedRequestId());
    }

    private static String suppliedRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(HttpHeaders.REQUEST_ID);
        }
        return null;
    }
}
