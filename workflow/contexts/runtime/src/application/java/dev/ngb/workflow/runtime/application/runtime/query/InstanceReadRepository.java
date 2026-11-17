package dev.ngb.workflow.runtime.application.runtime.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The history read side, shaped for the API and carrying no domain aggregate. */
public interface InstanceReadRepository {

    /**
     * @param definitionId a definition to filter by, or null
     * @param status a status to filter by, or null
     * @param correlationKey a correlation key to filter by, or null
     */
    List<InstanceView> list(UUID definitionId, String status, String correlationKey, int limit, int offset);

    Optional<InstanceView> findById(UUID instanceId);
}
