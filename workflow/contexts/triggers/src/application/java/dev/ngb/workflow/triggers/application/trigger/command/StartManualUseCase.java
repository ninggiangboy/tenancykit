package dev.ngb.workflow.triggers.application.trigger.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.api.instance.StartRequest;
import dev.ngb.workflow.runtime.api.instance.StartedInstance;
import java.util.UUID;

/**
 * Starts a workflow from a manual call: the definition is resolved to its newest published version, and the caller's
 * idempotency key makes a repeated call the same instance.
 */
public class StartManualUseCase {

    public record Command(
            UUID definitionId,
            Object input,
            String correlationKey,
            String idempotencyKey,
            String requestHash,
            String startedBy) {}

    private final UnitOfWork unitOfWork;
    private final VersionReader versions;
    private final InstanceStarter starter;

    public StartManualUseCase(UnitOfWork unitOfWork, VersionReader versions, InstanceStarter starter) {
        this.unitOfWork = unitOfWork;
        this.versions = versions;
        this.starter = starter;
    }

    public StartedInstance execute(Command command) {
        PublishedGraph version = unitOfWork
                .transactional(() -> versions.latest(command.definitionId()))
                .orElseThrow(() -> new NotFoundException("No published version of this workflow exists here."));
        return starter.start(new StartRequest(
                version.versionId(),
                command.input(),
                command.correlationKey(),
                command.startedBy(),
                command.idempotencyKey(),
                command.requestHash()));
    }
}
