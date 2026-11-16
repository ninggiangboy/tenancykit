package dev.ngb.crm.activity.application.activity.command;

import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.activity.application.activity.exception.ActivityProblem;
import dev.ngb.crm.activity.application.activity.exception.InvalidActivityBatchException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /activities/batch}: the same appending, once per item, each in its own transaction and in the order sent.
 *
 * <p>An activity the service refuses fails alone and reports its code; the batch as a whole is never rolled back
 * because of it, and repeating the whole batch is safe because an item that carries an {@code activityRef} is
 * idempotent by it. Only the envelope itself — no items, or more than one call may hold — fails the call, because a
 * caller that got the shape wrong should learn it once rather than read a thousand results.
 */
public class RecordActivitiesBatchUseCase {

    /** The outcome of an activity the service refused; every other outcome is the single call's own. */
    public static final String FAILED = "FAILED";

    /**
     * One activity's result, in the order the items were sent.
     *
     * @param activityRef the caller's reference as sent, or null when it carried none
     * @param outcome created, replayed or failed
     * @param code the failure code when the item failed, null otherwise
     */
    public record Result(String activityRef, String outcome, String code) {}

    private final UnitOfWork unitOfWork;
    private final RecordActivityUseCase record;
    private final int maxActivities;

    public RecordActivitiesBatchUseCase(
            UnitOfWork unitOfWork, RecordActivityUseCase record, int maxActivities) {
        this.unitOfWork = unitOfWork;
        this.record = record;
        this.maxActivities = maxActivities;
    }

    /** @return one result per item, in the order sent */
    public List<Result> execute(List<RecordActivityUseCase.Command> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidActivityBatchException(
                    InvalidActivityBatchException.INVALID_REQUEST, "The request must carry at least one activity.");
        }
        if (items.size() > maxActivities) {
            throw new InvalidActivityBatchException(
                    InvalidActivityBatchException.BATCH_LIMIT_EXCEEDED,
                    "An activity batch carries at most " + maxActivities + " activities.");
        }
        List<Result> results = new ArrayList<>(items.size());
        for (RecordActivityUseCase.Command item : items) {
            results.add(apply(item));
        }
        return List.copyOf(results);
    }

    private Result apply(RecordActivityUseCase.Command item) {
        String reference = item == null ? null : trimmed(item.activityRef());
        try {
            // One transaction per item: a failure rolls that item back and leaves the ones around it appended.
            RecordActivityUseCase.Result recorded = unitOfWork.transactional(() -> record.execute(item));
            return new Result(reference, recorded.outcome().name(), null);
        } catch (ActivityProblem refused) {
            return new Result(reference, FAILED, refused.code());
        } catch (IdempotencyConflictException conflict) {
            return new Result(reference, FAILED, "IDEMPOTENCY_CONFLICT");
        }
    }

    private static String trimmed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }
}
