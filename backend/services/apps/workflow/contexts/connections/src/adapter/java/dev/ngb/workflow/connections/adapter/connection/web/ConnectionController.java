package dev.ngb.workflow.connections.adapter.connection.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.connections.application.connection.command.ManageConnectionUseCase;
import dev.ngb.workflow.connections.application.connection.query.ConnectionQueries;
import dev.ngb.workflow.connections.application.connection.query.ConnectionView;
import dev.ngb.workflow.connections.domain.connection.Connection;
import dev.ngb.workflow.shared.WorkflowPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connections a Tenant may call. A secret goes in and never comes out: every response names the key id that wrote
 * the envelope instead.
 */
@Tag(
        name = "Connections",
        description =
                "The connections a workflow step may call. A secret goes in and never comes out.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ConnectionController {

    public record ConnectionRequest(
            String name, String kind, String allowedHost, String secret, Map<String, String> headers) {}

    public record ConnectionResponse(
            String id,
            String name,
            String kind,
            String allowedHost,
            Map<String, String> headers,
            String keyId,
            Instant verifiedAt,
            Instant createdAt,
            Instant updatedAt) {

        static ConnectionResponse of(Connection connection) {
            return new ConnectionResponse(
                    connection.id().toString(),
                    connection.name(),
                    connection.kind().name(),
                    connection.allowedHost(),
                    connection.headers(),
                    connection.secretKeyId(),
                    connection.verifiedAt(),
                    connection.createdAt(),
                    connection.updatedAt());
        }

        static ConnectionResponse ofView(ConnectionView view) {
            return new ConnectionResponse(
                    view.id().toString(),
                    view.name(),
                    view.kind(),
                    view.allowedHost(),
                    view.headers(),
                    view.keyId(),
                    view.verifiedAt(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    private final ManageConnectionUseCase connections;
    private final ConnectionQueries queries;

    public ConnectionController(ManageConnectionUseCase connections, ConnectionQueries queries) {
        this.connections = connections;
        this.queries = queries;
    }

    @Operation(
            summary = "Create a connection",
            description =
                    "Registers a connection a workflow step may call: a name, a `kind`, the one `allowedHost` it may "
                    + "reach, optional `headers` and a `secret`. The secret goes in as plaintext, is stored as an encrypted "
                    + "envelope and is never returned; every response names the key id that wrote the envelope instead. ")
    @PostMapping("/connections")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public ResponseEntity<ConnectionResponse> create(@RequestBody ConnectionRequest request) {
        Connection created = connections.create(new ManageConnectionUseCase.CreateCommand(
                request.name(), request.kind(), request.allowedHost(), request.secret(), request.headers()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(created.version()))
                .body(ConnectionResponse.of(created));
    }

    @Operation(
            summary = "List connections",
            description =
                    "Returns the tenant's connections without their secrets.")
    @GetMapping("/connections")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public List<ConnectionResponse> list() {
        return queries.all().stream().map(ConnectionResponse::ofView).toList();
    }

    @Operation(
            summary = "Get a connection",
            description =
                    "Returns one connection without its secret; its version is also the response's `ETag`. An unknown id "
                    + "answers `404`.")
    @GetMapping("/connections/{id}")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public ResponseEntity<ConnectionResponse> get(@PathVariable String id) {
        ConnectionView view = queries.get(UUID.fromString(id));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(view.version()))
                .body(ConnectionResponse.ofView(view));
    }

    @Operation(
            summary = "Update a connection",
            description =
                    "Replaces the connection's name, kind, allowed host and headers, and its secret when one is sent. "
                    + "Pass the current `ETag` in `If-Match`; a stale version is refused with `412`. Requires "
                    + "`workflow:connection:manage`.")
    @PutMapping("/connections/{id}")
    @ConditionalUpdate
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public ResponseEntity<ConnectionResponse> update(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ConnectionRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new dev.ngb.workflow.connections.application.connection.exception.PreconditionRequiredException(
                    "If-Match is required: send the version this change is based on.");
        }
        long version = connections.update(new ManageConnectionUseCase.UpdateCommand(
                UUID.fromString(id),
                ifMatch,
                request.name(),
                request.kind(),
                request.allowedHost(),
                request.secret(),
                request.headers()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(version))
                .body(ConnectionResponse.ofView(queries.get(UUID.fromString(id))));
    }
}
