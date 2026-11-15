package dev.ngb.email.sending.application.campaign.query;

/**
 * Campaign statistics, aggregated live. {@code opened} counts distinct emails whose pixel loaded and
 * {@code openEvents} counts total loads: a mail client that prefetches (Apple Mail Privacy Protection) inflates both,
 * so an open is a signal, not proof of intent.
 */
public record CampaignStatsView(
        long recipients,
        long published,
        long sent,
        long failed,
        long skipped,
        long opened,
        long openEvents,
        long clicked,
        long clickEvents,
        long unsubscribed) {}
