package dev.ngb.notification.dispatch.application.notification.service;

import java.time.Duration;

/**
 * Bounded retention per table. Without a purge the in-app bodies, the inbox and the dispatch ledger grow forever, and
 * the claim that a body is kept briefly becomes false.
 */
public record HousekeepingSettings(
        boolean enabled,
        Duration notificationRetention,
        Duration dispatchRetention,
        Duration inboxRetention,
        Duration deadOutboxRetention,
        int batchSize) {}
