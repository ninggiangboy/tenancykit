package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.notification.dispatch.application.notification.command.MarkNotificationReadUseCase;
import dev.ngb.notification.dispatch.application.notification.query.ListNotificationsQuery;
import dev.ngb.notification.dispatch.application.notification.query.NotificationListing;
import dev.ngb.notification.dispatch.application.notification.query.NotificationView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own inbox. The user comes from the token; another user's id is a 404 by construction. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    /** One row of the caller's inbox. */
    record NotificationResponse(
            String id,
            String type,
            String templateKey,
            int templateVersion,
            String locale,
            String title,
            String body,
            String link,
            Instant readAt,
            Instant createdAt) {

        static NotificationResponse of(NotificationView view) {
            return new NotificationResponse(
                    view.id().toString(),
                    view.type(),
                    view.templateKey(),
                    view.templateVersion(),
                    view.locale(),
                    view.title(),
                    view.body(),
                    view.link(),
                    view.readAt(),
                    view.createdAt());
        }
    }

    private final ListNotificationsQuery list;
    private final MarkNotificationReadUseCase markRead;

    NotificationController(ListNotificationsQuery list, MarkNotificationReadUseCase markRead) {
        this.list = list;
        this.markRead = markRead;
    }

    /**
     * The caller's own inbox, filtered, sorted and paged by the list-query contract. Unread rows are
     * {@code filter[read_at][is_null]=true}; {@code page_info=true} adds the limit, offset, total and next cursor.
     */
    @GetMapping
    @Listing(NotificationListing.class)
    @Authenticated
    Page<NotificationResponse> list(ListQuery query) {
        return list.list(query).map(NotificationResponse::of);
    }

    @PostMapping("/{id}/read")
    @Authenticated
    ResponseEntity<Void> read(@PathVariable UUID id) {
        markRead.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Authenticated
    ResponseEntity<Void> readAll() {
        markRead.markAllRead();
        return ResponseEntity.noContent().build();
    }
}
