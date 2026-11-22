package dev.ngb.survey.collection.adapter.guard.web;

import dev.ngb.survey.collection.application.guard.service.ClientFingerprint;
import dev.ngb.survey.collection.application.guard.service.PublicRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.OptionalLong;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The two limits every public endpoint carries, applied before a request reaches a controller.
 *
 * <p>The body limit is read from {@code Content-Length} and, when a client lies or streams, enforced again by the
 * container; 256 KiB is far more than a survey submit and far less than a request that could cost anything. The rate
 * limit is one token bucket per caller fingerprint per instance, and it answers {@code 429} with {@code Retry-After}
 * rather than failing the request outright, so an honest client can wait.
 */
public class PublicGuardFilter extends OncePerRequestFilter {

    private final PublicRateLimiter limiter;
    private final ClientFingerprint fingerprints;
    private final int maxBodyBytes;

    public PublicGuardFilter(PublicRateLimiter limiter, ClientFingerprint fingerprints, int maxBodyBytes) {
        this.limiter = limiter;
        this.fingerprints = fingerprints;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/p/") || path.startsWith("/survey/p/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.sendError(413, "the body is larger than this endpoint accepts");
            return;
        }
        String fingerprint = fingerprints.today(request.getRemoteAddr());
        OptionalLong wait = limiter.refuse(fingerprint);
        if (wait.isPresent()) {
            response.setHeader("Retry-After", Long.toString(wait.getAsLong()));
            response.sendError(429, "the caller's allowance is spent");
            return;
        }
        // A public page must not be indexed: a search engine that crept one link would publish a respondent's survey.
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        chain.doFilter(request, response);
    }
}
