package dev.ngb.mailroom.audience.adapter.suppression.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.audience.application.audience.query.AudienceQueries;
import dev.ngb.mailroom.audience.application.audience.query.SuppressionListing;
import dev.ngb.mailroom.audience.application.audience.query.SuppressionView;
import dev.ngb.mailroom.audience.application.suppression.command.AddSuppressionUseCase;
import dev.ngb.mailroom.audience.application.suppression.command.DeleteSuppressionUseCase;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionScope;
import dev.ngb.mailroom.shared.MailroomPermissions;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suppressions. Removing one is the Tenant's explicit statement that the customer consented again; it is the only
 * action that re-subscribes an address.
 */
@RestController
@RequestMapping("/api/v1/suppressions")
class SuppressionController {

    private final AddSuppressionUseCase add;
    private final DeleteSuppressionUseCase remove;
    private final AudienceQueries queries;

    SuppressionController(
            AddSuppressionUseCase add, DeleteSuppressionUseCase remove, AudienceQueries queries) {
        this.add = add;
        this.remove = remove;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link SuppressionListing}. */
    @GetMapping
    @Listing(SuppressionListing.class)
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    Page<SuppressionResponse> list(ListQuery query) {
        return queries.suppressions(query).map(SuppressionResponse::of);
    }

    @PostMapping
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    SuppressionResponse add(@RequestBody SuppressionRequest request) {
        SuppressionScope scope = SuppressionScope.valueOf(request.scope());
        add.execute(request.email(), scope);
        return new SuppressionResponse(request.email().trim().toLowerCase(java.util.Locale.ROOT), scope.name(), "MANUAL", null);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    void remove(@RequestParam String email) {
        remove.execute(email);
    }

    record SuppressionRequest(String email, String scope) {}

    record SuppressionResponse(String email, String scope, String reason, Instant createdAt) {

        static SuppressionResponse of(SuppressionView view) {
            return new SuppressionResponse(
                    view.email(), view.scope().name(), view.reason().name(), view.createdAt());
        }
    }
}
