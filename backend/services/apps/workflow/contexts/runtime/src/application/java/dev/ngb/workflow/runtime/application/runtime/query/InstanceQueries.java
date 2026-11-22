package dev.ngb.workflow.runtime.application.runtime.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads one Tenant's history. The read opens its own unit of work, because row-level security reads the bound tenant. */
public class InstanceQueries {

    private final UnitOfWork unitOfWork;
    private final InstanceReadRepository instances;
    private final VersionReader versions;

    public InstanceQueries(UnitOfWork unitOfWork, InstanceReadRepository instances, VersionReader versions) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.versions = versions;
    }

    public List<InstanceView> list(
            String status, String definitionCode, String correlationKey, int limit, int offset) {
        int bounded = Math.clamp(limit, 1, 200);
        int skip = Math.max(0, offset);
        UUID definitionId = null;
        if (definitionCode != null && !definitionCode.isBlank()) {
            Optional<UUID> resolved = unitOfWork.transactional(
                    () -> versions.graphOf(definitionCode, null).map(version -> version.definitionId()));
            if (resolved.isEmpty()) {
                return List.of();
            }
            definitionId = resolved.get();
        }
        UUID filter = definitionId;
        return unitOfWork.transactional(() -> {
            List<InstanceView> rows = instances.list(filter, status, correlationKey, bounded, skip);
            List<InstanceView> named = new ArrayList<>(rows.size());
            for (InstanceView row : rows) {
                named.add(withCode(row));
            }
            return List.copyOf(named);
        });
    }

    public InstanceView get(UUID instanceId) {
        return unitOfWork.transactional(() -> instances
                .findById(instanceId)
                .map(this::withCode)
                .orElseThrow(() -> new NotFoundException("No workflow instance exists here.")));
    }

    private InstanceView withCode(InstanceView view) {
        String code = versions.definitionCode(view.definitionId()).orElse("");
        return new InstanceView(
                view.id(),
                view.status(),
                view.definitionId(),
                code,
                view.version(),
                view.correlationKey(),
                view.failureReason(),
                view.startedAt(),
                view.finishedAt(),
                view.liveTokens(),
                view.steps(),
                view.rowVersion());
    }
}
