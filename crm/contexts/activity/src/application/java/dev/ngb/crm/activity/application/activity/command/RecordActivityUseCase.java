package dev.ngb.crm.activity.application.activity.command;

import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.activity.application.activity.exception.ActivityCustomerErasedException;
import dev.ngb.crm.activity.application.activity.exception.ActivityCustomerUnknownException;
import dev.ngb.crm.activity.application.activity.exception.ActivityReplayConflictException;
import dev.ngb.crm.activity.application.activity.service.ActivityPayloads;
import dev.ngb.crm.activity.application.activity.port.ActivityRepository;
import dev.ngb.crm.activity.application.activity.service.ActivityRules;
import dev.ngb.crm.activity.domain.activity.Activity;
import dev.ngb.crm.activity.domain.activity.ActivityId;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.customers.api.customer.CustomerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code POST /activities}: a system of the Tenant appends one thing a customer did to its timeline.
 *
 * <p>The type is the writer's own string, so a new source posts a new type and nothing here changes. The customer is
 * resolved through the customers context and is never created: an activity is a statement about a customer this
 * service already holds.
 *
 * <p>An optional {@code activityRef} makes the call replayable. The same reference with the same body answers the
 * stored activity, and the same reference with another body is a conflict, so a caller that retries a batch cannot
 * append one fact twice.
 */
public class RecordActivityUseCase {

    /** What the call did: appended the activity, or found the identical one already stored. */
    public enum Outcome {
        CREATED,
        REPLAYED
    }

    /**
     * One activity as the Tenant's system sent it. {@code payload} is the raw JSON value, so a payload that is not an
     * object is refused here rather than bound into a map that cannot hold it.
     */
    public record Command(
            String externalRef, String type, Instant occurredAt, String source, Object payload, String activityRef) {}

    public record Result(Activity activity, Outcome outcome) {

        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    private final UnitOfWork unitOfWork;
    private final ActivityRepository activities;
    private final CustomerDirectory customers;
    private final ActivityPayloads payloads;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final int maxPayloadBytes;
    private final Duration maxFutureSkew;

    public RecordActivityUseCase(
            UnitOfWork unitOfWork,
            ActivityRepository activities,
            CustomerDirectory customers,
            ActivityPayloads payloads,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            int maxPayloadBytes,
            Duration maxFutureSkew) {
        this.unitOfWork = unitOfWork;
        this.activities = activities;
        this.customers = customers;
        this.payloads = payloads;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxFutureSkew = maxFutureSkew;
    }

    public Result execute(Command command) {
        Instant now = clock.now();
        ActivityRules.Planned planned = ActivityRules.plan(command, now, maxFutureSkew, payloads, maxPayloadBytes);
        return unitOfWork.transactional(() -> {
            if (planned.activityRef() != null) {
                Optional<Activity> existing = activities.findByActivityRef(planned.activityRef());
                if (existing.isPresent()) {
                    return replay(existing.get(), planned);
                }
            }
            Activity activity = Activity.recorded(
                    new ActivityId(ids.newId()),
                    tenantContext.requireTenant(),
                    customer(planned.externalRef()).customerId(),
                    planned.type(),
                    planned.occurredAt(),
                    planned.source(),
                    planned.payload(),
                    planned.activityRef(),
                    now);
            if (!activities.insertIfAbsent(activity)) {
                // The insert lost the race with a concurrent first-time record of the same reference; the row that
                // won is the activity this call meant to append, so its body decides replay or conflict.
                Activity winner = activities
                        .findByActivityRef(planned.activityRef())
                        .orElseThrow(() -> new IllegalStateException(
                                "the activity that won the insert of this reference is not readable"));
                return replay(winner, planned);
            }
            return new Result(activity, Outcome.CREATED);
        });
    }

    /** The customer the activity belongs to; an activity never brings a customer into existence. */
    private CustomerRef customer(String externalRef) {
        Optional<CustomerRef> found = customers.findByExternalRef(externalRef);
        if (found.isEmpty()) {
            throw new ActivityCustomerUnknownException(
                    "No customer exists here with that reference. Record the customer before its activities.");
        }
        if (found.get().erased()) {
            throw new ActivityCustomerErasedException(
                    "The customer of this activity was erased; use a new reference for a new customer.");
        }
        return found.get();
    }

    /** The same reference and the same body: the stored activity is the answer, and nothing is written. */
    private Result replay(Activity stored, ActivityRules.Planned planned) {
        if (!digest(stored).equals(digest(planned))) {
            throw new ActivityReplayConflictException("The same activityRef was sent before with a different body.");
        }
        return new Result(stored, Outcome.REPLAYED);
    }

    private String digest(Activity stored) {
        return Fingerprint.of(stored.type(), stored.occurredAt().toString(), stored.source(),
                payloads.toJson(stored.payload()));
    }

    private String digest(ActivityRules.Planned planned) {
        return Fingerprint.of(planned.type(), planned.occurredAt().toString(), planned.source(),
                payloads.toJson(planned.payload()));
    }
}
