package dev.ngb.attribution.attribution.application.conversion.command;

import dev.ngb.attribution.attribution.application.conversion.port.ConversionRepository;
import dev.ngb.attribution.attribution.application.conversion.service.ConversionAttributor;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Settles a batch of pending conversions: build each chain, compute its credit and store it.
 *
 * <p>A conversion is settled only once it is older than the settle delay, so a page view that names the same visitor
 * from a click that arrived seconds later is still inside the chain. One conversion's failure does not stop the batch;
 * it stays pending and the next run retries it.
 */
public class SettleConversionsUseCase {

    /** What a settle pass produced. */
    public record Result(int settled, int unattributed, int failed) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ConversionRepository conversions;
    private final ConversionAttributor attributor;

    public SettleConversionsUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ConversionRepository conversions,
            ConversionAttributor attributor) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.conversions = conversions;
        this.attributor = attributor;
    }

    /**
     * @param pending the conversions to settle, already selected by the caller under the same tenant
     */
    public Result execute(List<Conversion> pending) {
        if (pending == null || pending.isEmpty()) {
            return new Result(0, 0, 0);
        }
        int settled = 0;
        int unattributed = 0;
        int failed = 0;
        for (Conversion conversion : pending) {
            try {
                // The chain read and the settle write run in one tenant transaction, because row-level security binds
                // the click and binding reads to the tenant setting the unit of work installs.
                ConversionAttributor.Outcome outcome = unitOfWork.transactional(() -> attributor.settle(conversion));
                unitOfWork.transactional(() -> conversions.settle(
                        outcome.conversion(), outcome.model(), outcome.credits()));
                if (outcome.conversion().creditStatus() == dev.ngb.attribution.attribution.domain.conversion
                        .CreditStatus.COMPUTED) {
                    settled++;
                } else {
                    unattributed++;
                }
            } catch (RuntimeException failure) {
                // The conversion stays pending and the next pass retries it; one bad chain does not lose the batch.
                failed++;
            }
        }
        return new Result(settled, unattributed, failed);
    }

    /** The settle-delay instant: a conversion created before this is old enough to settle. */
    public static Instant settleBefore(Instant now, java.time.Duration delay) {
        return now.minus(delay);
    }
}
