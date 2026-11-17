package dev.ngb.booking.gate.adapter.publicgate.web;

import dev.ngb.booking.gate.application.service.GateLimits;
import dev.ngb.booking.gate.application.settings.service.GateSettings;
import dev.ngb.booking.gate.application.settings.port.GateSettingsRepository;
import dev.ngb.booking.gate.domain.token.CustomerClaims;
import dev.ngb.booking.gate.domain.token.CustomerTokens;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public gate's front door, in the order that costs least first: a body limit, a honeypot, the per-instance rate
 * bucket, then token verification, then the tenant from the host.
 *
 * <p>The tenant scope is bound here because the ordinary host filter deliberately skips the public paths. A token whose
 * signed tenant is not the host's tenant is refused with the same answer as a missing token, so neither failure tells
 * a prober which part was wrong. CORS echoes only a registered origin, never a wildcard and never credentials.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PublicGateFilter extends OncePerRequestFilter {

    private static final String PREFLIGHT_ALLOW_HEADERS =
            "Authorization, Content-Type, Idempotency-Key, X-Queue-Ticket, X-Honeypot";

    private final SuiteHostResolver hosts;
    private final TenantContext tenantContext;
    private final UnitOfWork unitOfWork;
    private final GateSettingsRepository settings;
    private final CustomerTokens.Keys keys;
    private final ClientFingerprint fingerprints;
    private final PublicRateLimiter limiter;
    private final GateLimits limits;
    private final Clock clock;

    PublicGateFilter(
            SuiteHostResolver hosts,
            TenantContext tenantContext,
            UnitOfWork unitOfWork,
            GateSettingsRepository settings,
            CustomerTokens.Keys keys,
            ClientFingerprint fingerprints,
            PublicRateLimiter limiter,
            GateLimits limits,
            Clock clock) {
        this.hosts = hosts;
        this.tenantContext = tenantContext;
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.keys = keys;
        this.fingerprints = fingerprints;
        this.limiter = limiter;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/p/") || uri.startsWith("/booking/p/") || uri.equals("/p") || uri.equals("/booking/p"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RequestScope> scope = tenantContext.callAs(
                RequestScope.platform(), () -> hosts.resolve(request.getServerName()));
        if (scope.isEmpty() || !(scope.get() instanceof RequestScope.Tenant)) {
            write(response, 404, "not-found", "This hostname belongs to no tenant.");
            return;
        }
        try {
            tenantContext.callAs(scope.get(), () -> {
                try {
                    gate(request, response, chain);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
                return null;
            });
        } catch (FilterFailure failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) failure.getCause();
        }
    }

    private void gate(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        GateSettings gate = unitOfWork.transactional(() -> settings.find().orElse(new GateSettings(java.util.List.of(), 900)));
        String origin = request.getHeader("Origin");
        boolean allowedOrigin = origin != null && gate.allowedOrigins().contains(origin);

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            if (allowedOrigin) {
                allow(response, origin);
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", PREFLIGHT_ALLOW_HEADERS);
                response.setHeader("Access-Control-Max-Age", "600");
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (allowedOrigin) {
            allow(response, origin);
        }

        if (request.getContentLengthLong() > limits.maxBodyBytes()) {
            write(response, 413, "PAYLOAD_TOO_LARGE", "The request body is too large.");
            return;
        }
        if (honeypot(request)) {
            write(response, 403, "REFUSED", "The request was refused.");
            return;
        }

        String tenant = tenantContext.requireTenant().value().toString();
        String fingerprint = fingerprints.of(
                tenant, clientAddress(request), request.getHeader("User-Agent"), clock.now());
        int wait = limiter.allowed(fingerprint, clock.now());
        if (wait > 0) {
            response.setHeader("Retry-After", Integer.toString(wait));
            write(response, 429, "RATE_LIMITED", "Too many requests; try again later.");
            return;
        }

        CustomerClaims claims = claims(request);
        if (claims == null) {
            write(response, 401, "CUSTOMER_UNAUTHENTICATED", "A customer token is required.");
            return;
        }
        if (claims.expired(clock.now())) {
            write(response, 401, "CUSTOMER_UNAUTHENTICATED", "The customer token has expired.");
            return;
        }
        request.setAttribute(GateAttributes.CLAIMS, claims);
        chain.doFilter(request, response);
    }

    private CustomerClaims claims(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        CustomerClaims claims = CustomerTokens.verify(authorization.substring(7).strip(), keys).orElse(null);
        if (claims == null || !claims.tenantId().equals(tenantContext.requireTenant())) {
            return null;
        }
        return claims;
    }

    private static boolean honeypot(HttpServletRequest request) {
        String header = request.getHeader("X-Honeypot");
        String query = request.getParameter("hp");
        return (header != null && !header.isBlank()) || (query != null && !query.isBlank());
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static void allow(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
    }

    private static void write(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\""
                + detail.replace("\"", "'") + "\"}");
    }

    private static final class FilterFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
