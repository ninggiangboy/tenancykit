package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.notification.dispatch.application.notification.query.ListDispatchesQuery;
import dev.ngb.notification.shared.NotificationPermissions;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dispatch ledger, behind {@code notification:dispatch:read}; it answers "why did this user get no email". */
@RestController
@RequestMapping("/api/v1/email-dispatches")
class DispatchController {

    private final ListDispatchesQuery list;

    DispatchController(ListDispatchesQuery list) {
        this.list = list;
    }

    @GetMapping
    @RequiresPermission(NotificationPermissions.DISPATCH_READ)
    ListDispatchesQuery.Page list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID before,
            @RequestParam(required = false, defaultValue = "25") int limit) {
        return list.list(status, before, limit);
    }
}
