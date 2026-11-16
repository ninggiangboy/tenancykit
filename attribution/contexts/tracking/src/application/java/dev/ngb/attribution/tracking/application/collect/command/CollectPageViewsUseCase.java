package dev.ngb.attribution.tracking.application.collect.command;

import dev.ngb.attribution.shared.redirect.ClickToken;
import dev.ngb.attribution.tracking.api.collect.PageViewEvent;
import dev.ngb.attribution.tracking.application.collect.port.PageViewStore;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one collected batch into stored page views.
 *
 * <p>An event is stored only when its click id is a token this app signed, which is what makes forged collection
 * useless: a caller that did not come through a redirect cannot name a click. The server also enforces consent — when
 * the client said no, the visitor and session ids are dropped here, not merely ignored by a report — so an identifier
 * that was never consented to cannot reach a row even if a client sent one.
 *
 * <p>A refused event still answers {@code 204} to the caller: a probe learns nothing about whether a token was valid, a
 * batch was too large or an origin was wrong. The refusal is counted and dropped.
 */
public class CollectPageViewsUseCase {

    /** What a batch produced, so the endpoint can count it. */
    public record Result(int stored, int refused) {}

    /** One raw event from the wire, before verification. */
    public record Incoming(
            String eventId,
            String clickId,
            String visitorId,
            String sessionId,
            String path,
            String occurredAt) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final PageViewStore store;
    private final IdGenerator ids;
    private final Map<String, String> clickSecrets;

    public CollectPageViewsUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            PageViewStore store,
            IdGenerator ids,
            Map<String, String> clickSecrets) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.store = store;
        this.ids = ids;
        this.clickSecrets = Map.copyOf(clickSecrets);
    }

    /**
     * @param events the batch, already bounded by the endpoint to its count and size limits
     * @param consent whether the visitor consented; when false every identifier is dropped
     * @param bot whether the request classified as a machine
     * @param now the instant the server received the batch
     */
    public Result execute(List<Incoming> events, boolean consent, boolean bot, Instant now) {
        if (events == null || events.isEmpty()) {
            return new Result(0, 0);
        }
        TenantId tenant = tenantContext.requireTenant();
        List<PageViewEvent> valid = new ArrayList<>(events.size());
        int refused = 0;
        for (Incoming incoming : events) {
            Optional<PageViewEvent> event = verify(incoming, tenant, consent, bot, now);
            if (event.isPresent()) {
                valid.add(event.get());
            } else {
                refused++;
            }
        }
        if (valid.isEmpty()) {
            return new Result(0, refused);
        }
        int stored = tenantContext.callAs(
                RequestScope.tenant(tenant), () -> unitOfWork.transactional(() -> store.store(valid)));
        return new Result(stored, refused);
    }

    private Optional<PageViewEvent> verify(
            Incoming incoming, TenantId tenant, boolean consent, boolean bot, Instant now) {
        if (incoming == null || incoming.clickId() == null || incoming.clickId().isBlank()) {
            return Optional.empty();
        }
        Optional<ClickToken.Verified> click = ClickToken.verify(incoming.clickId(), clickSecrets);
        if (click.isEmpty()) {
            // Only a click this app issued may be named, so a page view with a made-up id is not stored.
            return Optional.empty();
        }
        String path = path(incoming.path());
        if (path == null) {
            return Optional.empty();
        }
        Instant occurredAt = parseInstant(incoming.occurredAt()).orElse(now);
        return Optional.of(new PageViewEvent(
                UUID.fromString(optionalUuid(incoming.eventId()).orElseGet(ids::newId).toString()),
                tenant,
                click.get().clickId(),
                // Consent is enforced here, not by the client: without it the identifiers do not reach the row.
                consent ? bounded(incoming.visitorId(), 128) : null,
                consent ? bounded(incoming.sessionId(), 128) : null,
                path,
                occurredAt,
                bot));
    }

    /**
     * The page's path only. A query string or fragment is dropped, so a landing page's own parameters and a search term
     * in a URL are never stored, and a value longer than the column allows is refused rather than stored truncated.
     */
    private static String path(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String path = raw.strip();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/") || path.length() > 2048) {
            return null;
        }
        return path;
    }

    private static Optional<UUID> optionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    /** A batch grouped by tenant, which the store's caller uses when a batch could name more than one. */
    public static Map<TenantId, List<PageViewEvent>> byTenant(List<PageViewEvent> events) {
        Map<TenantId, List<PageViewEvent>> groups = new LinkedHashMap<>();
        for (PageViewEvent event : events) {
            groups.computeIfAbsent(event.tenantId(), ignored -> new ArrayList<>()).add(event);
        }
        return groups;
    }
}
