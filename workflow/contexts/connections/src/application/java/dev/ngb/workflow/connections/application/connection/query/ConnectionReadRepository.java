package dev.ngb.workflow.connections.application.connection.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side; it never selects the ciphertext. */
public interface ConnectionReadRepository {

    List<ConnectionView> all();

    Optional<ConnectionView> findById(UUID id);
}
