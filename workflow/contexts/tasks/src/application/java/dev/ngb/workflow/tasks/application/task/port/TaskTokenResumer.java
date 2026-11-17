package dev.ngb.workflow.tasks.application.task.port;

import java.util.UUID;

/** What the task context tells the runtime when a task ends: the parked token walks on with the decision. */
public interface TaskTokenResumer {

    boolean resume(UUID tokenId, String branch, Object payload);
}
