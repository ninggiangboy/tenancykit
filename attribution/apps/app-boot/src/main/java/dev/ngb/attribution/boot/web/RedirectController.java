package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.links.adapter.redirect.cache.LinkCache;
import dev.ngb.attribution.links.adapter.redirect.ratelimit.PublicRateLimiter;
import dev.ngb.attribution.links.adapter.redirect.suspect.SuspectTracker;
import dev.ngb.attribution.links.adapter.redirect.unlock.UnlockAttempts;
import dev.ngb.attribution.links.adapter.redirect.web.ClickFactory;
import dev.ngb.attribution.links.api.redirect.ClickToken;
import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.redirect.service.RedirectDecider;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.redirect.RedirectLocation;
import dev.ngb.attribution.tracking.api.ingest.ClickIngest;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.UserAgentClassifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect: {@code GET /go/{slug}} resolves a link from memory, applies its rules and answers {@code 302}
 * with the signed click id appended, or one of four refusals.
 *
 * <p>It never fails or slows because measurement fails: the click is offered to a bounded buffer and the answer is sent
 * either way. The only database touch is a capped link's conditional counter, which exists so its cap is exact.
 *
 * <p>Every refusal reveals as little as possible. Unknown, archived, paused and entitlement-less all answer the same
 * {@code 404}; expired and cap-reached answer {@code 410}; a protected link answers {@code 401} and takes its password
 * by {@code POST}. Nothing here logs the address, the user agent, the token, the destination's query or a password.
 */
@RestController
public class RedirectController {

    private static final String NO_STORE = "no-store";
    private static final MediaType PROBLEM = MediaType.valueOf("application/problem+json");
    private static final String PASSWORD_REQUIRED = "{\"error\":\"password_required\"}";
    private static final long UNLOCK_RETRY_SECONDS = 60;

    private final LinkCache cache;
    private final RedirectDecider decider;
    private final ClickFactory clicks;
    private final ClickIngest ingest;
    private final ClickParamProvider clickParam;
    private final PasswordHasher passwords;
    private final PublicRateLimiter visitors;
    private final PublicRateLimiter links;
    private final UnlockAttempts unlockAttempts;
    private final SuspectTracker suspect;
    private final EntitlementGate entitlements;
    private final ClickTokenKeys keys;
    private final TenantContext tenantContext;
    private final Clock clock;

    public RedirectController(
            LinkCache cache,
            RedirectDecider decider,
            ClickFactory clicks,
            ClickIngest ingest,
            ClickParamProvider clickParam,
            PasswordHasher passwords,
            PublicRateLimiter visitors,
            PublicRateLimiter links,
            UnlockAttempts unlockAttempts,
            SuspectTracker suspect,
            EntitlementGate entitlements,
            ClickTokenKeys keys,
            TenantContext tenantContext,
            Clock clock) {
        this.cache = cache;
        this.decider = decider;
        this.clicks = clicks;
        this.ingest = ingest;
        this.clickParam = clickParam;
        this.passwords = passwords;
        this.visitors = visitors;
        this.links = links;
        this.unlockAttempts = unlockAttempts;
        this.suspect = suspect;
        this.entitlements = entitlements;
        this.keys = keys;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    /** A click token's signing keys, so the controller signs with the active one and never reads a token back. */
    public record ClickTokenKeys(String activeKeyId, Map<String, String> secrets) {

        String sign(UUID clickId) {
            return ClickToken.sign(clickId, activeKeyId, secrets.get(activeKeyId));
        }
    }

    @PublicEndpoint
    @RequestMapping(path = "/go/{slug}", method = RequestMethod.GET)
    public void redirect(
            @PathVariable String slug,
            @RequestParam(name = "e", required = false) String entry,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        answer(slug, entry, request, response, true);
    }

    /** Answered like {@code GET} without recording a click, so a link preview's probe spends nothing. */
    @PublicEndpoint
    @RequestMapping(path = "/go/{slug}", method = RequestMethod.HEAD)
    public void head(
            @PathVariable String slug,
            @RequestParam(name = "e", required = false) String entry,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        answer(slug, entry, request, response, false);
    }

    /** The unlock: a protected link answers {@code 302} on a correct password and {@code 403} otherwise. */
    @PublicEndpoint
    @PostMapping(path = "/go/{slug}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void unlock(
            @PathVariable String slug,
            @RequestParam(name = "e", required = false) String entry,
            @RequestBody(required = false) UnlockRequest body,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        tenantContext.requireTenant();
        String visitorHash = clicks.visitorHash(request);
        long visitorWait = visitors.acquire("v:" + visitorHash);
        if (visitorWait > 0) {
            tooMany(response, visitorWait);
            return;
        }
        Optional<Link> resolved = cache.find(tenantId(), slug);
        if (resolved.isEmpty() || !entitlements.enabled()) {
            notFound(response);
            return;
        }
        Link link = resolved.get();
        long linkWait = links.acquire("l:" + link.id());
        if (linkWait > 0) {
            tooMany(response, linkWait);
            return;
        }
        Optional<String> hash = decider.passwordHashOf(link, clock.now());
        if (hash.isEmpty()) {
            // Not live, or not protected: an unlock against it answers like an unknown slug, never like a wrong password.
            notFound(response);
            return;
        }
        if (!unlockAttempts.allowed(link.id().toString(), visitorHash)) {
            tooMany(response, UNLOCK_RETRY_SECONDS);
            return;
        }
        String presented = body == null ? null : body.password();
        if (presented == null || !passwords.matches(presented, hash.get())) {
            unlockAttempts.recordFailure(link.id().toString(), visitorHash);
            forbidden(response);
            return;
        }
        unlockAttempts.clear(link.id().toString(), visitorHash);
        deliverAfterUnlock(link, entry, request, response);
    }

    private void answer(
            String slug, String entry, HttpServletRequest request, HttpServletResponse response, boolean recordClick)
            throws IOException {
        tenantContext.requireTenant();
        String visitorHash = clicks.visitorHash(request);
        long visitorWait = visitors.acquire("v:" + visitorHash);
        if (visitorWait > 0) {
            tooMany(response, visitorWait);
            return;
        }
        Optional<Link> resolved = cache.find(tenantId(), slug);
        if (resolved.isEmpty() || !entitlements.enabled()) {
            notFound(response);
            return;
        }
        Link link = resolved.get();
        long linkWait = links.acquire("l:" + link.id());
        if (linkWait > 0) {
            tooMany(response, linkWait);
            return;
        }
        if (!recordClick) {
            // A HEAD must not spend a capped link's click, so it answers where the link would send a visitor and stops.
            RedirectDecider.Decision peeked =
                    decider.peek(link, device(request), clicks.countryOf(request), clock.now());
            if (peeked instanceof RedirectDecider.Decision.Redirect redirect) {
                sendFound(response, location(link, redirect.destination(), ""), false);
            } else {
                refuse(peeked, response);
            }
            return;
        }
        RedirectDecider.Decision decision =
                decider.decide(link, device(request), clicks.countryOf(request), clock.now());
        switch (decision) {
            case RedirectDecider.Decision.Redirect redirect ->
                    deliver(link, redirect.destination(), entry, request, response);
            case RedirectDecider.Decision.PasswordRequired ignored -> passwordRequired(response);
            default -> refuse(decision, response);
        }
    }

    /** The unlock chose a live link, so it spends and selects without re-deciding the password. */
    private void deliverAfterUnlock(
            Link link, String entry, HttpServletRequest request, HttpServletResponse response) throws IOException {
        RedirectDecider.Decision decision =
                decider.spendAndSelect(link, device(request), clicks.countryOf(request));
        if (decision instanceof RedirectDecider.Decision.Redirect redirect) {
            deliver(link, redirect.destination(), entry, request, response);
            return;
        }
        refuse(decision, response);
    }

    private void deliver(
            Link link, Destination destination, String entry, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        UUID clickId = clicks.newClickId();
        String token = keys.sign(clickId);
        boolean suspectClick = suspect.recordAndCheck(clicks.visitorHash(request), link.id().toString());
        sendFound(response, location(link, destination, token), true);
        // Offered after the answer is written: a full buffer or a broker outage must never change what the visitor sees.
        ingest.offer(clicks.click(request, link, destination, clickId, entry, suspectClick));
    }

    private String location(Link link, Destination destination, String token) {
        return RedirectLocation.build(destination.url(), link.utm(), ClickFactory.clickParam(clickParam), token);
    }

    private void refuse(RedirectDecider.Decision decision, HttpServletResponse response) throws IOException {
        if (decision instanceof RedirectDecider.Decision.Gone) {
            response.setStatus(HttpServletResponse.SC_GONE);
            response.setHeader("Cache-Control", NO_STORE);
        } else {
            notFound(response);
        }
    }

    private void sendFound(HttpServletResponse response, String location, boolean noStore) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        if (noStore) {
            response.setHeader("Cache-Control", NO_STORE);
        }
    }

    private void notFound(HttpServletResponse response) {
        // One constant answer for an unknown slug, an archived or paused link and a tenant without the entitlement.
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setHeader("Cache-Control", NO_STORE);
    }

    private void passwordRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("Cache-Control", NO_STORE);
        response.setContentType(PROBLEM.toString());
        response.getOutputStream().write(PASSWORD_REQUIRED.getBytes(StandardCharsets.UTF_8));
    }

    private void forbidden(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("Cache-Control", NO_STORE);
    }

    private void tooMany(HttpServletResponse response, long retryAfterSeconds) {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        response.setHeader("Cache-Control", NO_STORE);
    }

    private String tenantId() {
        return tenantContext.requireTenant().value().toString();
    }

    private static DeviceClass device(HttpServletRequest request) {
        return UserAgentClassifier.classify(request.getHeader("User-Agent")).device();
    }

    /** The unlock request body: the password and nothing else, so no other field can be smuggled in. */
    public record UnlockRequest(String password) {}
}
