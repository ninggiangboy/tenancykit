package dev.ngb.workflow.connections.adapter.credential.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.workflow.connections.application.credential.command.RegisterCredentialUseCase;
import dev.ngb.workflow.connections.application.credential.query.CredentialQueries;
import dev.ngb.workflow.connections.application.credential.query.CredentialView;
import dev.ngb.workflow.shared.WorkflowPermissions;
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

    @PutMapping("/platform-credential")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public ResponseEntity<CredentialResponse> register(@RequestBody RegisterRequest request) {
        RegisterCredentialUseCase.Receipt receipt =
                register.execute(new RegisterCredentialUseCase.Command(request.clientId(), request.secret()));
        CredentialView view = queries.get();
        return ResponseEntity.ok(
                new CredentialResponse(receipt.clientId(), receipt.status(), receipt.keyId(), view.updatedAt()));
    }

    @GetMapping("/platform-credential")
    @RequiresPermission(WorkflowPermissions.CONNECTION_MANAGE)
    public CredentialResponse status() {
        CredentialView view = queries.get();
        return new CredentialResponse(view.clientId(), view.status(), view.keyId(), view.updatedAt());
    }
}
