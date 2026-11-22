package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.boot.web.CollectRateLimiter;
import dev.ngb.attribution.tracking.application.collect.command.CollectPageViewsUseCase;
import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.UserAgentClassifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public collection endpoint a Tenant's page calls: a batch of page views, a session and a consent flag.
 *
 * <p>Everything that is not acceptable answers {@code 204} without storing and without explaining why: a bad signature,
 * a foreign origin, an oversize body, a batch over the limit or a rate refusal all look the same, so a probe learns
 * nothing. That is the one shape the plan requires, and it is why this controller never returns a problem body.
 *
 * <p>The server enforces consent: with {@code consent=false} the use case drops the visitor and session ids, so no
 * identifier reaches a row even if a client sent one. The snippet is what asks the visitor; the server is what makes the
 * answer true.
 */
@Tag(
        name = "Page-view collection",
        description =
                "The public endpoint a tenant's page snippet posts page views to. Anything unacceptable answers 204 "
                + "without storing.")
@RestController
@Callers(Caller.PUBLIC)
public class CollectController {

    /** The most events one request may carry, matching the snippet's own batch. */
    static final int MAX_EVENTS = 20;

    /** The largest body one request may carry, so a hostile client cannot stream a page into memory. */
    static final int MAX_BODY_BYTES = 8 * 1024;

    /** How many events one click id may store in a day. */
    static final int PER_CLICK_DAILY = 500;

    /** How many requests one visitor hash may make in a minute. */
    static final int PER_VISITOR_PER_MINUTE = 120;

    private final CollectPageViewsUseCase useCase;
    private final OriginPolicy origins;
    private final CollectRateLimiter visitors;
    private final ClickDailyLimiter clicks;
    private final TenantContext tenantContext;
    private final Clock clock;

    public CollectController(
            CollectPageViewsUseCase useCase,
            OriginPolicy origins,
            CollectRateLimiter visitors,
            ClickDailyLimiter clicks,
            TenantContext tenantContext,
            Clock clock) {
        this.useCase = useCase;
        this.origins = origins;
        this.visitors = visitors;
        this.clicks = clicks;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Operation(
            summary = "Collect page views from a tenant's site",
            description =
                    "The public endpoint a tenant's page snippet posts a batch of page views, a session and a `consent` "
                    + "flag to. Everything not acceptable (a bad signature, a foreign origin, an oversize body, a batch "
                    + "over the limit, a rate refusal) answers the same `204` without storing anything, so a probe learns "
                    + "nothing. With `consent=false` the server drops the visitor and session ids, so no identifier reaches "
                    + "a row. An event is stored only when its click id is a token this app signed. Public: no suite token "
                    + "is needed.")
    @PostMapping(path = "/t/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PublicEndpoint
    void collect(
            @RequestBody(required = false) EventBatch body,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        tenantContext.requireTenant();
        // A preflight is answered by Spring's CORS handling; a POST from a foreign origin is refused here, silently.
        if (!origins.allowed(request.getHeader("Origin"), request.getHeader("Host"))) {
            noContent(response);
            return;
        }
        if (body == null || body.events() == null || body.events().isEmpty() || body.events().size() > MAX_EVENTS) {
            noContent(response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            noContent(response);
            return;
        }
        boolean bot = UserAgentClassifier.classify(request.getHeader("User-Agent")).traffic()
                != dev.ngb.core.visitor.TrafficClass.HUMAN;
        boolean consented = body.consent();

        // Per-click daily limit: a page view that names a click over the day's budget is dropped rather than stored.
        if (!clicks.allow(body.events().stream().map(RawEvent::clickId).toList())) {
            noContent(response);
            return;
        }
        long wait = visitors.acquire("c:" + tenantContext.requireTenant().value());
        if (wait > 0) {
            noContent(response);
            return;
        }
        useCase.execute(toIncoming(body.events()), consented, bot, clock.now());
        noContent(response);
    }

    private static List<CollectPageViewsUseCase.Incoming> toIncoming(List<RawEvent> events) {
        return events.stream()
                .map(event -> new CollectPageViewsUseCase.Incoming(
                        event.eventId(), event.clickId(), event.visitorId(), event.sessionId(), event.path(),
                        event.occurredAt()))
                .toList();
    }

    private static void noContent(HttpServletResponse response) {
        response.setStatus(204);
        response.setHeader("Cache-Control", "no-store");
    }

    /** One collected page view as the snippet sends it. */
    public record RawEvent(
            String eventId,
            String clickId,
            String visitorId,
            String sessionId,
            String path,
            String occurredAt) {}

    /** The batch, with the visitor's consent and nothing else that the server does not need. */
    public record EventBatch(List<RawEvent> events, boolean consent) {}

    /** Whether a `page_events` insert is over the per-click daily budget. */
    @FunctionalInterface
    public interface ClickDailyLimiter {
        /** @param clickIds the tokens the batch names; one over its budget refuses the whole batch */
        boolean allow(List<String> clickIds);
    }

    /** Whether a request's origin may post to this tenant. */
    @FunctionalInterface
    public interface OriginPolicy {
        boolean allowed(String origin, String host);
    }
}
