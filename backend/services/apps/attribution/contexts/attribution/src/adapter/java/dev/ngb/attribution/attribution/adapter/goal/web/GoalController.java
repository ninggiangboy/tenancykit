package dev.ngb.attribution.attribution.adapter.goal.web;

import dev.ngb.attribution.attribution.application.goal.command.CreateGoalUseCase;
import dev.ngb.attribution.attribution.application.goal.command.UpdateGoalUseCase;
import dev.ngb.attribution.attribution.application.goal.query.GoalListing;
import dev.ngb.attribution.attribution.application.goal.query.GoalQueries;
import dev.ngb.attribution.attribution.application.goal.query.GoalView;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A tenant's goals: the conversion keys its site and its backend report, edited under {@code If-Match}. */
@Tag(
        name = "Goals",
        description =
                "The conversion keys a tenant's site and backend report.")
@RestController
@RequestMapping("/goals")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class GoalController {

    private final CreateGoalUseCase create;
    private final UpdateGoalUseCase update;
    private final GoalQueries queries;
    private final UnitOfWork unitOfWork;

    public GoalController(CreateGoalUseCase create, UpdateGoalUseCase update, GoalQueries queries, UnitOfWork unitOfWork) {
        this.create = create;
        this.update = update;
        this.queries = queries;
        this.unitOfWork = unitOfWork;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link GoalListing}. */
    @Operation(
            summary = "List goals",
            description =
                    "Pages through the tenant's goals, with the list-query contract's filtering, sorting and paging. ")
    @GetMapping
    @Listing(GoalListing.class)
    @RequiresPermission(AttributionPermissions.GOAL_READ)
    Page<GoalResponse> list(ListQuery query) {
        return queries.list(query).map(GoalResponse::ofView);
    }

    @Operation(
            summary = "Create a goal",
            description =
                    "Creates a goal: a conversion `key` the tenant's site and backend both report, a name, and an "
                    + "optional default value and currency. Two goals in one tenant cannot share a key. Answers `201`. ")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(AttributionPermissions.GOAL_MANAGE)
    GoalResponse create(@RequestBody GoalRequest request) {
        return GoalResponse.of(create.execute(new CreateGoalUseCase.Command(
                request.key(), request.name(), decimal(request.defaultValue()), request.currency())));
    }

    @Operation(
            summary = "Get a goal",
            description =
                    "Returns one goal; its version is also the response's `ETag`. An unknown id answers `404`. Requires "
                    + "`attribution:goal:read`.")
    @GetMapping("/{id}")
    @RequiresPermission(AttributionPermissions.GOAL_READ)
    ResponseEntity<GoalResponse> get(@PathVariable UUID id) {
        GoalView goal = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(goal.version())).body(GoalResponse.ofView(goal));
    }

    @Operation(
            summary = "Edit a goal",
            description =
                    "Changes the goal's name, default value and currency. Pass the current `ETag` in `If-Match`; a stale "
                    + "version is refused with `412`.")
    @PatchMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(AttributionPermissions.GOAL_MANAGE)
    ResponseEntity<GoalResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody GoalRequest request) {
        Goal updated = update.execute(
                id,
                new UpdateGoalUseCase.Change(request.name(), decimal(request.defaultValue()), request.currency()),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(updated.version())).body(GoalResponse.of(updated));
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
    }

    record GoalRequest(String key, String name, String defaultValue, String currency) {}

    record GoalResponse(
            UUID id, String key, String name, String defaultValue, String currency, String status) {

        static GoalResponse ofView(GoalView goal) {
            return new GoalResponse(
                    goal.id(), goal.key(), goal.name(),
                    goal.defaultValue() == null ? null : goal.defaultValue().toPlainString(),
                    goal.currency(), goal.status());
        }

        static GoalResponse of(Goal goal) {
            return new GoalResponse(
                    goal.id(),
                    goal.key(),
                    goal.name(),
                    goal.defaultValue() == null ? null : goal.defaultValue().toPlainString(),
                    goal.currency(),
                    goal.status().name());
        }
    }
}
