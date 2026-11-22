package dev.ngb.billing.invoicing.application.cycle.port;

import dev.ngb.billing.sharedkernel.id.WalletId;
import java.time.Instant;

/** Where a period that could not be closed is reported. The application layer has no logger, so the implementation logs. */
@FunctionalInterface
public interface CycleFailureRecorder {

    /**
     * @param walletId the wallet whose period could not be billed
     * @param cycleStart the start of the period that was being closed
     * @param cycleEnd the end of that period
     * @param cause why the period could not be closed
     */
    void failed(WalletId walletId, Instant cycleStart, Instant cycleEnd, RuntimeException cause);

    /** Keeps a unit test that builds the use case by hand readable. */
    CycleFailureRecorder NONE = (walletId, cycleStart, cycleEnd, cause) -> {};
}
