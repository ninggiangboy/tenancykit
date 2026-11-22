package dev.ngb.mailroom.sending.application.email.port;

/**
 * The tenant's effective sender and retention settings: a stored row overlaid on the configured defaults, so a
 * caller never has to decide what a null means.
 */
public record EmailSettingsView(
        String fromKey,
        String fromName,
        String replyTo,
        int bodyRetentionDays,
        int campaignPublishPerMinute,
        long version) {}
