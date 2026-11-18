package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.notification.dispatch.application.notification.query.DispatchListing;
import dev.ngb.notification.dispatch.application.notification.query.DispatchView;
import dev.ngb.notification.dispatch.application.notification.query.ListDispatchesQuery;
import dev.ngb.notification.shared.NotificationPermissions;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dispatch ledger, behind {@code notification:dispatch:read}; it answers "why did this user get no email". */
@RestController
@RequestMapping("/api/v1/email-dispatches")
class DispatchController {

    /** One ledger row for the auditor; the recipient is returned only to a caller holding the dispatch permission. */
    record DispatchResponse(
            String id,
            String sourceEventId,
            String notificationType,
            String userId,
            String recipient,
            String fromKey,
            String templateKey,
            int templateVersion,
            String locale,
            String subject,
            String status,
            String reason,
            Instant createdAt,
            Instant publishedAt) {

        static DispatchResponse of(DispatchView view) {
            return new DispatchResponse(
                    view.id().toString(),
                    view.sourceEventId().toString(),
                    view.notificationType(),
                    view.userId().toString(),
                    view.recipient(),
                    view.fromKey(),
                    view.templateKey(),
                    view.templateVersion(),
                    view.locale(),
                    view.subject(),
                    view.status(),
                    view.reason(),
                    view.createdAt(),
                    view.publishedAt());
        }
    }

    private final ListDispatchesQuery list;

    DispatchController(ListDispatchesQuery list) {
        this.list = list;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link DispatchListing}. */
    @GetMapping
    @Listing(DispatchListing.class)
    @RequiresPermission(NotificationPermissions.DISPATCH_READ)
    Page<DispatchResponse> list(ListQuery query) {
        return list.list(query).map(DispatchResponse::of);
    }
}
