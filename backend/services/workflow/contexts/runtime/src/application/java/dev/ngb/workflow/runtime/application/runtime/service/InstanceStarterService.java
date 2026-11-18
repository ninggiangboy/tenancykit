package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.api.instance.StartRequest;
import dev.ngb.workflow.runtime.api.instance.StartedInstance;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.domain.instance.Instance;

/** Serves the other contexts the one way to start a workflow. The class name is what {@code api-implementations-are-services} requires. */
public class InstanceStarterService implements InstanceStarter {

    private final StartInstanceUseCase start;

    public InstanceStarterService(StartInstanceUseCase start) {
        this.start = start;
    }

    @Override
    public StartedInstance start(StartRequest request) {
        Instance instance = start.execute(new StartInstanceUseCase.Command(
                request.versionId(),
                request.input(),
                request.correlationKey(),
                request.startedBy(),
                request.idempotencyKey(),
                request.requestHash()));
        return new StartedInstance(instance.id(), instance.status().name());
    }
}
