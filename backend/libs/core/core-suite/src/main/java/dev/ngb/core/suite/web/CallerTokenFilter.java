package dev.ngb.core.suite.web;

import dev.ngb.core.names.ActorNames;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.ParseException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a valid token of the wrong kind on each prefix, before the resource server looks the identity up: only a
 * Tenant System or service-client suite token may call {@code /system/v1} or {@code /internal/v1}, and only a Keycloak
 * user token may call {@code /api/v1} or {@code /admin/v1}. A request with no token is left to the resource server, which
 * answers {@code 401}; this filter answers {@code 403}, because the token is genuine and the caller is simply at the
 * wrong door.
 *
 * <p>The rule is read from the path, not from the handler, because routing happens after the filter chain and a token of
 * the wrong kind must be refused even for a path that has no handler at all: a {@code /system/v1} twin that does not
 * exist answers {@code 403} to a person and {@code 404} to a machine, rather than telling a person the route exists.
 */
public class CallerTokenFilter extends OncePerRequestFilter {

    /** The problem type a token of the wrong kind answers with. */
    public static final String WRONG_TOKEN_KIND = "wrong-token-kind";

    private final SuiteJwtDecoders decoders;

    public CallerTokenFilter(SuiteJwtDecoders decoders) {
        this.decoders = decoders;
    }

    /**
     * What a prefix accepts, read from the raw token before verifying it.
     *
     * @param prefix the request's route prefix, or null when the path is not prefix-scoped
     * @param platformIssuer whether the token's issuer is Platform's
     * @param subject the token's subject, for telling a Tenant System from a service client
     * @return true when the token may call the prefix
     */
    public static boolean accepts(String prefix, boolean platformIssuer, String subject) {
        if (prefix == null) {
            return true;
        }
        return switch (prefix) {
            case "system" -> platformIssuer && subject != null && subject.startsWith(ActorNames.TENANT_SYSTEM_SUBJECT);
            case "internal" -> platformIssuer && subject != null && subject.startsWith(ActorNames.SERVICE_SUBJECT);
            case "api", "admin" -> !platformIssuer;
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7).trim();
        String issuer;
        String subject;
        try {
            var claims = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet();
            issuer = claims.getIssuer();
            subject = claims.getSubject();
        } catch (ParseException malformed) {
            // Not a JWT at all: the resource server answers 401, which is the right answer for an unreadable token.
            chain.doFilter(request, response);
            return;
        }
        if (!accepts(prefixOf(request.getRequestURI()), decoders.isPlatformIssuer(issuer), subject)) {
            SuiteProblems.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    WRONG_TOKEN_KIND,
                    "This token is not accepted on this prefix.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The route's prefix segment, or null when the path is not one a caller prefix owns. */
    private static String prefixOf(String uri) {
        if (matches(uri, "/system/")) {
            return "system";
        }
        if (matches(uri, "/internal/")) {
            return "internal";
        }
        if (matches(uri, "/api/")) {
            return "api";
        }
        if (matches(uri, "/admin/")) {
            return "admin";
        }
        return null;
    }

    private static boolean matches(String uri, String prefix) {
        return uri.equals(prefix.substring(0, prefix.length() - 1)) || uri.startsWith(prefix);
    }
}
