package dev.ngb.customer.activity.adapter.activity.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.customer.activity.application.activity.command.RecordActivitiesBatchUseCase;
import dev.ngb.customer.activity.application.activity.command.RecordActivityUseCase;
import dev.ngb.customer.activity.application.activity.query.ActivityQueries;
import dev.ngb.customer.activity.application.activity.query.ActivityView;
import dev.ngb.customer.activity.domain.activity.Activity;
import dev.ngb.customer.shared.CustomerPermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's activities: anything its own systems report that a customer did.
 *
 * <p>{@code type} is the writer's free string, so a new source — a receipt printed, an app opened — posts a new type
 * and nothing here changes. An optional {@code activityRef} makes a call replayable: the same reference and body
 * answers the stored activity with {@code 200}, another body under the same reference is a {@code 409}.
 *
 * <p>The batch is {@code 200} even when some items failed: a backfill must not restart from the beginning over one bad
 * item, and every item that carries a reference is idempotent by it.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ActivityController {

    /**
     * One activity as the Tenant's system reports it. {@code payload} is a raw JSON value so a body that is not an
     * object is refused with the documented code rather than failing to bind.
     */
    public record ActivityRequest(
            String externalRef,
            String type,
            Instant occurredAt,
            String source,
            Object payload,
            String activityRef) {}

    /** One activity as the API returns it, with the customer's reference so a caller need not resolve the id. */
    public record ActivityResponse(
            String id,
            String customerId,
            String externalRef,
            String type,
            Instant occurredAt,
            String source,
            Map<String, Object> payload,
            String activityRef,
            Instant createdAt) {

        static ActivityResponse of(Activity activity, String externalRef) {
            return new ActivityResponse(
                    activity.id().toString(),
                    activity.customerId().toString(),
                    externalRef,
                    activity.type(),
                    activity.occurredAt(),
                    activity.source(),
                    activity.payload(),
                    activity.activityRef(),
                    activity.createdAt());
        }

        static ActivityResponse ofView(ActivityView view, String externalRef) {
            return new ActivityResponse(
                    view.id().toString(),
                    view.customerId().toString(),
                    externalRef,
                    view.type(),
                    view.occurredAt(),
                    view.source(),
                    view.payload(),
                    view.activityRef(),
                    view.createdAt());
        }
    }

    public record ActivityBatchRequest(List<ActivityRequest> items) {}

    /** One item's outcome, keyed by its position and its reference, so a caller can match results to what it sent. */
    public record BatchItemResult(int index, String ref, String outcome, String code) {}

    public record ActivityBatchResponse(List<BatchItemResult> results) {}

    private final RecordActivityUseCase record;
    private final RecordActivitiesBatchUseCase batch;
    private final ActivityQueries activityQueries;

    public ActivityController(
            RecordActivityUseCase record,
            RecordActivitiesBatchUseCase batch,
            ActivityQueries activityQueries) {
        this.record = record;
        this.batch = batch;
        this.activityQueries = activityQueries;
    }

    /** Appends the activity, or replays the one the same body already appended under this reference. */
    @PostMapping("/activities")
    @RepeatableCreate
    @RequiresPermission(CustomerPermissions.ACTIVITY_RECORD)
    public ResponseEntity<ActivityResponse> recordActivity(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) ActivityRequest request) {
        RecordActivityUseCase.Result result = record.execute(command(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ActivityResponse.of(result.activity(), externalRefOf(request)));
    }

    /** Appends up to a thousand activities, each in its own transaction and reported on its own. */
    @PostMapping("/activities/batch")
    @RequiresPermission(CustomerPermissions.ACTIVITY_RECORD)
    public ActivityBatchResponse recordActivities(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) ActivityBatchRequest request) {
        List<RecordActivityUseCase.Command> items = request == null || request.items() == null
                ? null
                : request.items().stream().map(ActivityController::command).toList();
        List<RecordActivitiesBatchUseCase.Result> results = batch.execute(items);
        List<BatchItemResult> rows = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            RecordActivitiesBatchUseCase.Result result = results.get(index);
            rows.add(new BatchItemResult(index, result.activityRef(), result.outcome(), result.code()));
        }
        return new ActivityBatchResponse(List.copyOf(rows));
    }

    /**
     * A page of the customer's activities, newest first. {@code type} narrows it to one writer's vocabulary and
     * {@code from}/{@code to} to a window of when things happened.
     */
    @GetMapping("/customers/{externalRef}/activities")
    @RequiresPermission(CustomerPermissions.ACTIVITY_READ)
    public Page<ActivityResponse> listCustomerActivities(
            @PathVariable String externalRef,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return activityQueries
                .forCustomer(externalRef, type, from, to, limit, cursor)
                .map(view -> ActivityResponse.ofView(view, externalRef.strip()));
    }

    private static RecordActivityUseCase.Command command(ActivityRequest request) {
        if (request == null) {
            return new RecordActivityUseCase.Command(null, null, null, null, null, null);
        }
        return new RecordActivityUseCase.Command(
                request.externalRef(),
                request.type(),
                request.occurredAt(),
                request.source(),
                request.payload(),
                request.activityRef());
    }

    private static String externalRefOf(ActivityRequest request) {
        return request == null || request.externalRef() == null ? null : request.externalRef().strip();
    }
}
