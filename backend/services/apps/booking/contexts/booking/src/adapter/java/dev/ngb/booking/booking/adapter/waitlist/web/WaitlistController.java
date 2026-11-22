package dev.ngb.booking.booking.adapter.waitlist.web;

import dev.ngb.booking.booking.application.waitlist.command.JoinWaitlistUseCase;
import dev.ngb.booking.booking.application.waitlist.command.LeaveWaitlistUseCase;
import dev.ngb.booking.booking.application.waitlist.query.WaitlistQueries;
import dev.ngb.booking.booking.application.waitlist.query.WaitlistView;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The waitlist: joining a full target, reading an entry with how many are ahead, and leaving.
 *
 * <p>An entry is offered an ordinary hold whose TTL is the tenant's offer window; confirming that hold accepts the
 * entry, and its expiry or cancellation lapses it and tries the next one. Booking guarantees order among waiters only:
 * a direct hold may take a freed place first.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class WaitlistController {

    public record WaitlistJoinRequest(
            UUID offeringId,
            String customerRef,
            Integer qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            UUID resourceId) {}

    public record WaitlistEntryResponse(
            UUID id,
            UUID offeringId,
            String customerRef,
            int qty,
            String status,
            int position,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            UUID offeredBookingId,
            Instant offeredUntil,
            Instant createdAt) {

        static WaitlistEntryResponse ofView(WaitlistView view) {
            return new WaitlistEntryResponse(
                    view.id(),
                    view.offeringId(),
                    view.customerRef(),
                    view.qty(),
                    view.status(),
                    view.position(),
                    view.sessionId(),
                    view.poolCode(),
                    view.startsAt(),
                    view.endsAt(),
                    view.offeredBookingId(),
                    view.offeredUntil(),
                    view.createdAt());
        }
    }

    public record WaitlistPage(List<WaitlistEntryResponse> data) {}

    private final JoinWaitlistUseCase joins;
    private final LeaveWaitlistUseCase leaves;
    private final WaitlistQueries entries;

    public WaitlistController(
            JoinWaitlistUseCase joins, LeaveWaitlistUseCase leaves, WaitlistQueries entries) {
        this.joins = joins;
        this.leaves = leaves;
        this.entries = entries;
    }

    @GetMapping("/waitlist")
    @RequiresPermission(BookingPermissions.WAITLIST_READ)
    public WaitlistPage listWaitlist() {
        return new WaitlistPage(entries.list().stream().map(WaitlistEntryResponse::ofView).toList());
    }

    @PostMapping("/waitlist")
    @RequiresPermission(BookingPermissions.WAITLIST_MANAGE)
    public ResponseEntity<WaitlistEntryResponse> joinWaitlist(@RequestBody WaitlistJoinRequest request) {
        JoinWaitlistUseCase.Result result = joins.execute(new JoinWaitlistUseCase.Command(
                request.offeringId(),
                request.customerRef(),
                request.qty(),
                request.sessionId(),
                request.poolCode(),
                request.startsAt(),
                request.endsAt(),
                request.resourceId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WaitlistEntryResponse(
                        result.entry().id(),
                        result.entry().offeringId(),
                        result.entry().customerRef(),
                        result.entry().qty(),
                        result.entry().status().name(),
                        result.position(),
                        result.entry().sessionId(),
                        result.entry().poolCode(),
                        result.entry().desiredStartsAt(),
                        result.entry().desiredEndsAt(),
                        result.entry().offeredBookingId(),
                        result.entry().offeredUntil(),
                        result.entry().createdAt()));
    }

    @GetMapping("/waitlist/{entryId}")
    @RequiresPermission(BookingPermissions.WAITLIST_READ)
    public WaitlistEntryResponse getWaitlistEntry(@PathVariable UUID entryId) {
        return WaitlistEntryResponse.ofView(entries.get(entryId));
    }

    @DeleteMapping("/waitlist/{entryId}")
    @RequiresPermission(BookingPermissions.WAITLIST_MANAGE)
    public ResponseEntity<Void> leaveWaitlist(@PathVariable UUID entryId) {
        leaves.execute(entryId);
        return ResponseEntity.noContent().build();
    }
}
