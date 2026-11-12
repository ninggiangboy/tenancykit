package dev.ngb.billing.boot.jobs;

import dev.ngb.core.messaging.broker.BrokerRelay;
import org.springframework.context.annotation.Profile;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Relays outbox events to the in-process consumers and to the broker for every active tenant. */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxJobs {

    private final TenantSweep sweep;
    private final OutboxRelay relay;
    private final BrokerRelay brokerRelay;

    OutboxJobs(TenantSweep sweep, OutboxRelay relay, BrokerRelay brokerRelay) {
        this.sweep = sweep;
        this.relay = relay;
        this.brokerRelay = brokerRelay;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.poll-delay:PT1S}")
    public void relay() {
        sweep.forEachActiveTenant(tenant -> {
            relay.relayDue();
            brokerRelay.relayDue();
        });
    }
}
