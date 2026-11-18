package dev.ngb.workflow.runtime.api.task;

import java.util.UUID;

/**
 * What another context tells the runtime when a parked token's reason to wait has ended: a task was completed or
 * expired. The update is a conditional WAITING -> READY move, so a task completed twice resumes once.
 */
public interface TaskResumer {

    boolean resume(UUID tokenId, String branch, Object payload);
}
