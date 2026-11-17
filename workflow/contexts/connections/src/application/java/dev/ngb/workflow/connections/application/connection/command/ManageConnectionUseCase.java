package dev.ngb.workflow.connections.application.connection.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.connections.application.connection.port.ConnectionRepository;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.domain.connection.Connection;
import dev.ngb.workflow.connections.domain.connection.ConnectionKind;
import java.util.Map;
import java.util.UUID;

/** Creates and edits a connection; the secret goes in as a plaintext and is stored as an envelope. */
public class ManageConnectionUseCase {

    public record CreateCommand(
            String name,
            String kind,
            String allowedHost,
            String secret,
            Map<String, String> headers) {}

    public record UpdateCommand(
            UUID id, long expectedVersion, String name, String kind, String allowedHost, String secret, Map<String, String> headers) {}

    private final UnitOfWork unitOfWork;
    private final ConnectionRepository connections;
    private final SecretCipher cipher;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ManageConnectionUseCase(
            UnitOfWork unitOfWork,
            ConnectionRepository connections,
            SecretCipher cipher,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.connections = connections;
        this.cipher = cipher;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Connection create(CreateCommand command) {
        TenantId tenant = tenantContext.requireTenant();
        Connection connection = new Connection(
                ids.newId(),
                tenant,
                command.name(),
                ConnectionKind.valueOf(command.kind()),
                command.allowedHost(),
                command.secret() == null ? null : cipher.encrypt(command.secret()),
                command.secret() == null ? null : cipher.keyId(),
                command.headers(),
                null,
                clock.now(),
                clock.now(),
                1);
        unitOfWork.transactional(() -> {
            connections.add(connection);
            return null;
        });
        return connection;
    }

    public long update(UpdateCommand command) {
        Connection current = unitOfWork
                .transactional(() -> connections.findById(command.id()))
                .orElseThrow(() -> new IllegalArgumentException("connection not found"));
        Connection next = new Connection(
                current.id(),
                current.tenantId(),
                command.name() == null ? current.name() : command.name(),
                command.kind() == null ? current.kind() : ConnectionKind.valueOf(command.kind()),
                command.allowedHost() == null ? current.allowedHost() : command.allowedHost(),
                command.secret() == null ? current.secretCiphertext() : cipher.encrypt(command.secret()),
                command.secret() == null ? current.secretKeyId() : cipher.keyId(),
                command.headers() == null ? current.headers() : command.headers(),
                current.verifiedAt(),
                current.createdAt(),
                clock.now(),
                command.expectedVersion());
        return unitOfWork.transactional(() -> connections.update(next, command.expectedVersion()));
    }
}
