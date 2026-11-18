package dev.ngb.workflow.connections.application.connection.port;

import dev.ngb.workflow.connections.domain.connection.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The Tenant's connections: created, edited and read back without ever returning the secret. */
public interface ConnectionRepository {

    void add(Connection connection);

    /** Replaces the settable members under the optimistic version and returns the version the row now holds. */
    long update(Connection connection, long expectedVersion);

    Optional<Connection> findById(UUID id);

    Optional<Connection> findByName(String name);

    List<Connection> all();
}
