package dev.ngb.survey.collection.domain.visit;

import dev.ngb.survey.collection.domain.visit.VisitState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One opening of one link, and everything the app learned about the arrival without keeping anything that identifies
 * the person.
 *
 * <p>What is stored is bounded and non-identifying: the link's source, the five UTM labels the browser sent, the
 * referrer's registrable domain, a device class, a browser family, a GeoIP country and whether the traffic looked
 * human. The client address and the user agent string are read in memory only, and the visit's own token is stored as
 * a hash.
 *
 * @param tokenHash SHA-256 of the opaque visit token the browser holds
 * @param trafficClass whether the user agent looked like a crawler; a bot is excluded from the funnel's counts
 */
public record Visit(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        UUID linkId,
        UUID versionId,
        int versionNo,
        byte[] tokenHash,
        String source,
        Map<String, String> utm,
        String referrerDomain,
        String deviceClass,
        String browserFamily,
        String country,
        String trafficClass,
        VisitState state,
        UUID responseId,
        Instant openedAt,
        Instant startedAt,
        Instant lastActivityAt,
        Instant completedAt) {

    public Visit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(linkId, "linkId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        if (versionNo < 1) {
            throw new IllegalArgumentException("a visit opens a version numbered from 1");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        utm = Map.copyOf(utm);
    }

    /** The visit a link's opening produces, at the state before any answer has been saved. */
    public static Visit opened(
            UUID id,
            UUID tenantId,
            UUID surveyId,
            UUID linkId,
            UUID versionId,
            int versionNo,
            byte[] tokenHash,
            Arrival arrival,
            Instant now) {
        return new Visit(
                id,
                tenantId,
                surveyId,
                linkId,
                versionId,
                versionNo,
                tokenHash,
                arrival.source(),
                arrival.utm(),
                arrival.referrerDomain(),
                arrival.deviceClass(),
                arrival.browserFamily(),
                arrival.country(),
                arrival.trafficClass(),
                VisitState.OPENED,
                null,
                now,
                null,
                now,
                null);
    }

    /** The visit after an answer was saved: the first save moves it to {@link VisitState#STARTED}. */
    public Visit started(UUID responseId, Instant now) {
        return new Visit(
                id, tenantId, surveyId, linkId, versionId, versionNo, tokenHash, source, utm, referrerDomain,
                deviceClass, browserFamily, country, trafficClass, VisitState.STARTED, responseId, openedAt, startedAt == null ? now : startedAt,
                now, completedAt);
    }

    /** The visit after the response was submitted. */
    public Visit completed(UUID responseId, Instant now) {
        return new Visit(
                id, tenantId, surveyId, linkId, versionId, versionNo, tokenHash, source, utm, referrerDomain,
                deviceClass, browserFamily, country, trafficClass, VisitState.COMPLETED, responseId, openedAt, startedAt == null ? now : startedAt,
                now, now);
    }

    /** The visit after one page view was recorded: the activity moves, the state does not. */
    public Visit touched(Instant now) {
        return new Visit(
                id, tenantId, surveyId, linkId, versionId, versionNo, tokenHash, source, utm, referrerDomain,
                deviceClass, browserFamily, country, trafficClass, state, responseId, openedAt, startedAt, now, completedAt);
    }

    /** Whether the sweep may mark this visit abandoned: it is unfinished and has been idle past the threshold. */
    public boolean idleSince(Duration threshold, Instant now) {
        boolean pending = state == VisitState.OPENED || state == VisitState.STARTED;
        return pending && lastActivityAt.plus(threshold).isBefore(now);
    }

    /** The visit the sweep abandons. */
    public Visit abandoned() {
        return new Visit(
                id, tenantId, surveyId, linkId, versionId, versionNo, tokenHash, source, utm, referrerDomain,
                deviceClass, browserFamily, country, trafficClass, VisitState.ABANDONED, responseId, openedAt, startedAt, lastActivityAt, completedAt);
    }
}
