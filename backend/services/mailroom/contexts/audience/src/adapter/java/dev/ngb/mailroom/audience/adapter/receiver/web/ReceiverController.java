package dev.ngb.mailroom.audience.adapter.receiver.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.audience.application.audience.query.AudienceQueries;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverListing;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverView;
import dev.ngb.mailroom.audience.application.receiver.command.BulkUpsertReceiversUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.DeleteReceiverUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.UpdateReceiverUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.UpsertReceiverUseCase;
import dev.ngb.mailroom.audience.domain.receiver.Receiver;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A tenant's own customers. The address is the identity; deleting a receiver is an erasure, not a soft delete. */
@Tag(
        name = "Receivers",
        description =
                "A tenant's own customers by email address. Deleting a receiver is an erasure, not a soft delete.")
@RestController
@RequestMapping("/api/v1/receivers")
class ReceiverController {

    private final UpsertReceiverUseCase upsert;
    private final UpdateReceiverUseCase update;
    private final DeleteReceiverUseCase delete;
    private final BulkUpsertReceiversUseCase bulk;
    private final AudienceQueries queries;

    ReceiverController(
            UpsertReceiverUseCase upsert,
            UpdateReceiverUseCase update,
            DeleteReceiverUseCase delete,
            BulkUpsertReceiversUseCase bulk,
            AudienceQueries queries) {
        this.upsert = upsert;
        this.update = update;
        this.delete = delete;
        this.bulk = bulk;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link ReceiverListing}. */
    @Operation(
            summary = "List receivers",
            description =
                    "Pages through the tenant's receivers, with the list-query contract's filtering, sorting and paging. "
                    + "Requires `mailroom:receiver:read`.")
    @GetMapping
    @Listing(ReceiverListing.class)
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    Page<ReceiverResponse> list(ListQuery query) {
        return queries.receivers(query).map(ReceiverResponse::of);
    }

    @Operation(
            summary = "Create or update a receiver by email",
            description =
                    "Upserts a receiver by `email`, the identity of a receiver. A second call with the same address "
                    + "updates the names and attributes and never re-subscribes the address; removing the suppression is "
                    + "the only way to do that. Requires `mailroom:receiver:manage`.")
    @PutMapping
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    ReceiverResponse upsert(@RequestBody ReceiverRequest request) {
        Receiver receiver = upsert.execute(new UpsertReceiverUseCase.Command(
                request.email(), request.firstName(), request.lastName(), request.attributes()));
        return ReceiverResponse.of(receiver);
    }

    @Operation(
            summary = "Get a receiver",
            description =
                    "Returns one receiver; its version is also the response's `ETag`. An unknown id answers `404`. "
                    + "Requires `mailroom:receiver:read`.")
    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    ResponseEntity<ReceiverResponse> get(@PathVariable UUID id) {
        ReceiverView view = queries.receiver(id);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(view.version()))
                .body(ReceiverResponse.of(view));
    }

    @Operation(
            summary = "Change a receiver's names and attributes",
            description =
                    "Changes the names and attributes of a receiver by id; the properties that are present replace the "
                    + "stored ones. The address and the consent status are not editable here. Pass the current `ETag` in "
                    + "`If-Match`; a stale version is refused. Requires `mailroom:receiver:manage`.")
    @PatchMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    ResponseEntity<ReceiverResponse> patch(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ReceiverPatch request) {
        Receiver receiver = update.execute(
                id,
                new UpdateReceiverUseCase.Change(request.firstName(), request.lastName(), request.attributes()),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(receiver.version()))
                .body(ReceiverResponse.of(receiver));
    }

    @Operation(
            summary = "Erase a receiver",
            description =
                    "Erases the receiver and its memberships, as a customer's deletion request requires. The suppression "
                    + "row is kept on purpose, so an address that is deleted and imported again is still not mailed. Pass "
                    + "the current `ETag` in `If-Match`; a stale version is refused. Answers `204`. Requires "
                    + "`mailroom:receiver:manage`.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    @Operation(
            summary = "Upsert receivers in bulk",
            description =
                    "Upserts up to 1,000 receivers in one call and reports a result per item by `index`. One bad address "
                    + "is an error for its own item, not for the call, so an import does not lose the rows around a typo. "
                    + "Requires `mailroom:receiver:manage`.")
    @PostMapping("/bulk")
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    List<BulkResult> bulk(@RequestBody BulkRequest request) {
        return bulk.execute(request.receivers().stream()
                        .map(item -> new BulkUpsertReceiversUseCase.Item(
                                item.email(), item.firstName(), item.lastName(), item.attributes()))
                        .toList())
                .stream()
                .map(result -> new BulkResult(result.index(), result.receiverId(), result.error()))
                .toList();
    }

    record ReceiverRequest(String email, String firstName, String lastName, Map<String, Object> attributes) {}

    record ReceiverPatch(String firstName, String lastName, Map<String, Object> attributes) {}

    record BulkRequest(List<ReceiverRequest> receivers) {}

    record BulkResult(int index, UUID receiverId, String error) {}

    record ReceiverResponse(
            UUID id,
            String email,
            String firstName,
            String lastName,
            Map<String, Object> attributes,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        static ReceiverResponse of(Receiver receiver) {
            return new ReceiverResponse(
                    receiver.id(), receiver.email(), receiver.firstName(), receiver.lastName(),
                    receiver.attributes(), receiver.status().name(), receiver.version(),
                    receiver.createdAt(), receiver.updatedAt());
        }

        static ReceiverResponse of(ReceiverView view) {
            return new ReceiverResponse(
                    view.id(), view.email(), view.firstName(), view.lastName(), view.attributes(),
                    view.status().name(), view.version(), view.createdAt(), view.updatedAt());
        }
    }
}
