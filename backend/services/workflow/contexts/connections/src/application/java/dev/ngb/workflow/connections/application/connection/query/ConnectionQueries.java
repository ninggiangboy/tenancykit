package dev.ngb.workflow.connections.application.connection.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's connections for the management GET handlers. */
public class ConnectionQueries {

    private final UnitOfWork unitOfWork;
    private final ConnectionReadRepository connections;

    public ConnectionQueries(UnitOfWork unitOfWork, ConnectionReadRepository connections) {
        this.unitOfWork = unitOfWork;
        this.connections = connections;
    }

    public List<ConnectionView> all() {
        return unitOfWork.transactional(connections::all);
    }

    public ConnectionView get(UUID id) {
        return unitOfWork.transactional(() -> connections
                .findById(id)
                .orElseThrow(() -> new NotFoundException("No workflow connection exists here.")));
    }
}
