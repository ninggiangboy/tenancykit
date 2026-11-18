package dev.ngb.tenant.boot.jobs;

import dev.ngb.tenant.resource.application.order.command.CreateOrderUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes what a crash left behind: a PENDING order whose reservation can no longer be live, and a CANCELING order
 * whose Billing cancel never completed.
 */
@Component
@ConditionalOnProperty(name = "tenant.resource.expiry-enabled", havingValue = "true", matchIfMissing = true)
class OrderExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final CreateOrderUseCase create;

    OrderExpiryJob(CreateOrderUseCase create) {
        this.create = create;
    }

    @Scheduled(fixedDelayString = "${tenant.resource.expiry-delay:PT1M}")
    public void tick() {
        int reaped = create.reapStaleOrders();
        if (reaped > 0) {
            LOG.info("execution: reaped {} stale orders", reaped);
        }
    }
}
