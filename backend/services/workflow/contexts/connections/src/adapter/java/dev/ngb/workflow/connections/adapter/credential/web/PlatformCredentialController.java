package dev.ngb.workflow.connections.adapter.credential.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.workflow.connections.application.credential.command.RegisterCredentialUseCase;
import dev.ngb.workflow.connections.application.credential.query.CredentialQueries;
import dev.ngb.workflow.connections.application.credential.query.CredentialView;
import dev.ngb.workflow.shared.WorkflowPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's Platform credential. The secret is write-only: it is accepted here, encrypted, and never read back; the
 * response says which key wrote the envelope and whether Platform still accepts it.
 */
@Tag(
        name = "Platform credential",
        description =
                "The tenant's write-only credential for calling Platform, and its status.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class PlatformCredentialController {

    public record RegisterRequest(String clientId, String secret) {}

    public record CredentialResponse(String clientId, String status, String keyId, Instant updatedAt) {}

    private final RegisterCredentialUseCase register;
    private final CredentialQueries queries;

    public PlatformCredentialController(RegisterCredentialUseCase register, CredentialQueries queries) {
        this.register = register;
        this.queries = queries;
    }

    @Operation(
            summary = "Register the tenant's Platform credential",
            description =
                    "Stores the API credential (`clientId` and `secret`) workflow uses to call Platform for the tenant. "
                    + "The secret is write-only: it is encrypted on the way in and never read back. Answers the "
                    + "credential's status and the key id that wrote it. Requires `workflow:connection:manage`.")
    @PutMapping("/platform-credential")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public ResponseEntity<CredentialResponse> register(@RequestBody RegisterRequest request) {
        RegisterCredentialUseCase.Receipt receipt =
                register.execute(new RegisterCredentialUseCase.Command(request.clientId(), request.secret()));
        CredentialView view = queries.get();
        return ResponseEntity.ok(
                new CredentialResponse(receipt.clientId(), receipt.status(), receipt.keyId(), view.updatedAt()));
    }

    @Operation(
            summary = "Get the Platform credential's status",
            description =
                    "Returns the credential's `clientId`, the key id that wrote its envelope, when it was last updated "
                    + "and whether Platform still accepts it. The secret is never returned. Requires "
                    + "`workflow:connection:manage`.")
    @GetMapping("/platform-credential")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public CredentialResponse status() {
        CredentialView view = queries.get();
        return new CredentialResponse(view.clientId(), view.status(), view.keyId(), view.updatedAt());
    }
}
