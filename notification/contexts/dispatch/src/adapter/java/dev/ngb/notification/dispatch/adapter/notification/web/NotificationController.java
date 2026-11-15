package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.Authenticated;
import dev.ngb.notification.dispatch.application.notification.command.MarkNotificationReadUseCase;
import dev.ngb.notification.dispatch.application.notification.query.ListNotificationsQuery;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own inbox. The user comes from the token; another user's id is a 404 by construction. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final ListNotificationsQuery list;
    private final MarkNotificationReadUseCase markRead;

    NotificationController(ListNotificationsQuery list, MarkNotificationReadUseCase markRead) {
        this.list = list;
        this.markRead = markRead;
    }

    @GetMapping
    @Authenticated
    ListNotificationsQuery.Page list(
            @RequestParam(required = false, defaultValue = "false") boolean unread,
            @RequestParam(required = false) UUID before,
            @RequestParam(required = false, defaultValue = "25") int limit) {
        return list.list(unread, before, limit);
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
