package dev.ngb.loyalty.program.application.program.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.domain.program.Program;
import java.util.Optional;

/** The Tenant's one program, as the command side writes it. */
public interface ProgramRepository {

    void add(Program program);

    void update(Program program);

    /** Reads the tenant's program under a row lock, so a replace cannot race another one. */
    Optional<Program> lock(TenantId tenantId);
}
