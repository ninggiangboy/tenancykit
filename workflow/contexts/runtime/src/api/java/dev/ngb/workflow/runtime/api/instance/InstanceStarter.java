package dev.ngb.workflow.runtime.api.instance;

/** The runtime's contract for the contexts that start work: a manual call, a trigger and a schedule all come through here. */
public interface InstanceStarter {

    StartedInstance start(StartRequest request);
}
