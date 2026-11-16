package dev.ngb.loyalty.program.application.program.query;

import dev.ngb.loyalty.program.api.program.ProgramView;
import java.util.Optional;

/** Read-only projection of the current tenant's program. */
public interface ProgramReadRepository {

    /** The tenant's program, or empty until the first write; row-level security supplies the tenant. */
    Optional<ProgramView> find();
}
