package dev.ngb.workflow.runtime.application.runtime.port;

import java.util.UUID;

/** The door the engine uses to cancel the open tasks of an instance it is abandoning. */
public interface TaskCanceller {

    /** Cancels every open task of the instance and answers how many it cancelled. */
    int cancelOpen(UUID instanceId);
}
