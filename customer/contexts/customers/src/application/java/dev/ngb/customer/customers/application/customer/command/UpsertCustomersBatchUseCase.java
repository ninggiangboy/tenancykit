package dev.ngb.customer.customers.application.customer.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.application.customer.exception.CustomerProblem;
import dev.ngb.customer.customers.application.customer.exception.InvalidBatchException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /customers/batch}: the same upsert, once per item, each in its own transaction and in the order sent.
 *
 * <p>Two items with one reference therefore apply in turn — the second sees what the first stored. An item the service
 * refuses fails alone and reports its code; the batch as a whole is never rolled back because of it, and repeating the
 * whole batch is safe because every item is idempotent by its reference. Only the envelope itself — no items, or more
 * than one call may carry — fails the call.
 */
public class UpsertCustomersBatchUseCase {

    /** The outcome of an item the service refused; every other outcome is the single upsert's own. */
    public static final String FAILED = "FAILED";

    /**
     * One item's result, in the order the items were sent.
     *
     * @param externalRef the item's reference as sent, or null when it carried none
     * @param outcome created, updated, unchanged or failed
     * @param code the failure code when the item failed, null otherwise
     */
    public record Result(String externalRef, String outcome, String code) {}

    private final UnitOfWork unitOfWork;
    private final UpsertCustomerUseCase upsert;
    private final int maxItems;

    public UpsertCustomersBatchUseCase(UnitOfWork unitOfWork, UpsertCustomerUseCase upsert, int maxItems) {
        this.unitOfWork = unitOfWork;
        this.upsert = upsert;
        this.maxItems = maxItems;
    }

    /** @return one result per item, in the order sent */
    public List<Result> execute(List<UpsertCustomerUseCase.Command> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidBatchException(
                    InvalidBatchException.INVALID_REQUEST, "The request must carry at least one customer.");
        }
        if (items.size() > maxItems) {
            throw new InvalidBatchException(
                    InvalidBatchException.BATCH_LIMIT_EXCEEDED,
                    "A customer batch carries at most " + maxItems + " items.");
        }
        List<Result> results = new ArrayList<>(items.size());
        for (UpsertCustomerUseCase.Command item : items) {
            results.add(apply(item));
        }
        return List.copyOf(results);
    }

    private Result apply(UpsertCustomerUseCase.Command item) {
        String externalRef = reference(item.externalRef());
        try {
            // One transaction per item: a failure rolls that item back and leaves the ones before and after it stored.
            UpsertCustomerUseCase.Result applied = unitOfWork.transactional(() -> upsert.execute(item));
            return new Result(externalRef, applied.outcome().name(), null);
        } catch (CustomerProblem refused) {
            return new Result(externalRef, FAILED, refused.code());
        }
    }

    private static String reference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }
}
